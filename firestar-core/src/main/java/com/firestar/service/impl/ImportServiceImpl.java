package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.common.ImportContext;
import com.firestar.common.ImportStatus;
import com.firestar.common.ExcelReadUtil;
import com.firestar.common.ExcelTemplateUtil;
import com.firestar.common.DataScopeHelper;
import com.firestar.dto.ImportColumnDef;
import com.firestar.entity.FileStorage;
import com.firestar.entity.ImportBatch;
import com.firestar.entity.User;
import com.firestar.exception.BizException;
import com.firestar.mapper.FileStorageMapper;
import com.firestar.mapper.ImportBatchMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.FileStorageService;
import com.firestar.service.ImportHandler;
import com.firestar.service.ImportService;
import com.firestar.service.NotificationService;
import com.firestar.dto.NotifyCommand;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 历史数据导入核心服务（方案 v3）。
 *
 * <p>【两阶段已改为异步流水线】submit 只做「存文件 + 落 QUEUED 批次 + 立即返回」，
 * 后台 importExecutor 跑 PARSING→VALIDATING→WRITING→RECONCILING→终态。
 * 全量校验通过才写库（有错整批一行不写）；单批 ≤2000 行；审计只留一条批次摘要。</p>
 *
 * <p>【事务】状态更新用 REQUIRES_NEW（失败时业务回滚但摘要仍在）；写入用独立
 * TransactionTemplate（整批原子，抛异常即回滚）。ThreadLocal ImportContext 让
 * AuditLogAspect 在导入线程跳过实体审计。</p>
 */
@Slf4j
@Service
public class ImportServiceImpl implements ImportService {

    private static final DateTimeFormatter BATCH_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024; // 5MB

    private final ImportBatchMapper batchMapper;
    private final FileStorageMapper fileStorageMapper;
    private final FileStorageService storage;
    private final UserMapper userMapper;
    private final NotificationService notificationService;
    private final Map<String, ImportHandler> handlers;
    private final Executor importExecutor;
    private final TransactionTemplate txWrite;     // 业务写入（REQUIRED，独立事务）
    private final TransactionTemplate txStatus;    // 状态更新（REQUIRES_NEW）

    public ImportServiceImpl(ImportBatchMapper batchMapper,
                             FileStorageMapper fileStorageMapper,
                             FileStorageService storage,
                             UserMapper userMapper,
                             NotificationService notificationService,
                             List<ImportHandler> handlerList,
                             @Qualifier("importExecutor") Executor importExecutor,
                             PlatformTransactionManager txManager) {
        this.batchMapper = batchMapper;
        this.fileStorageMapper = fileStorageMapper;
        this.storage = storage;
        this.userMapper = userMapper;
        this.notificationService = notificationService;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(ImportHandler::key, Function.identity(), (a, b) -> a));
        this.importExecutor = importExecutor;
        this.txWrite = new TransactionTemplate(txManager);
        this.txStatus = new TransactionTemplate(txManager);
        this.txStatus.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 重启恢复：扫描 active_flag=1 的批次重新入队（单批事务中断会自动回滚，重跑整批即可）。 */
    @PostConstruct
    public void resumeUnfinished() {
        try {
            List<ImportBatch> active = batchMapper.selectList(new LambdaQueryWrapper<ImportBatch>()
                    .eq(ImportBatch::getActiveFlag, 1));
            for (ImportBatch b : active) {
                dispatch(b.getId());
                log.info("[import] 重启恢复批次 {}", b.getBatchNo());
            }
        } catch (Exception e) {
            // 表可能尚未建（首次启动 Flyway 未跑），不阻断启动
            log.warn("[import] 重启恢复扫描跳过：{}", e.getMessage());
        }
    }

    @Override
    public ImportHandler requireHandler(String key) {
        ImportHandler h = handlers.get(key);
        if (h == null) throw new BizException("未知的导入类型：" + key);
        return h;
    }

    @Override
    public List<Map<String, Object>> listKeys() {
        List<Map<String, Object>> out = new ArrayList<>();
        handlers.values().stream().sorted((a, b) -> a.key().compareTo(b.key())).forEach(h -> {
            Map<String, Object> m = new HashMap<>();
            m.put("key", h.key());
            m.put("displayName", h.displayName());
            out.add(m);
        });
        return out;
    }

    @Override
    public String submit(String key, MultipartFile file, Long fileStorageId, Long operatorId, String parentBatchNo) {
        requireHandler(key);
        if (fileStorageId == null) throw new BizException("缺少原始文件 fileStorageId");
        if (parentBatchNo != null && !parentBatchNo.isBlank()) {
            // 修正批次：目标必须是本人提交的 FAILED 批次
            ImportBatch parent = batchMapper.selectOne(new LambdaQueryWrapper<ImportBatch>()
                    .eq(ImportBatch::getBatchNo, parentBatchNo));
            if (parent == null || !ImportStatus.STATUS_FAILED.equals(parent.getStatus())) {
                throw new BizException("原批次不存在或不是失败状态，无法修正重传");
            }
            if (parent.getOperatorId() != null && !parent.getOperatorId().equals(operatorId)) {
                throw new BizException("无权修正他人导入的批次");
            }
        }

        String batchNo = "IMP-" + LocalDate.now().format(BATCH_DATE) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        ImportBatch b = new ImportBatch();
        b.setBatchNo(batchNo);
        b.setImportKey(key);
        b.setOperatorId(operatorId);
        b.setFileStorageId(fileStorageId);
        b.setFileName(file.getOriginalFilename());
        b.setParentBatchNo(parentBatchNo == null || parentBatchNo.isBlank() ? null : parentBatchNo);
        b.setPhase(ImportStatus.PHASE_QUEUED);
        b.setStatus(ImportStatus.STATUS_QUEUED);
        b.setActiveFlag(0); // 先落 0，dispatch 里置 1（避免同 key 并发撞唯一键前就占坑）
        b.setTotalRows(0);
        b.setSuccessRows(0);
        b.setFailRows(0);
        b.setProcessedRows(0);
        b.setProgressPct(0);
        b.setNotifySent(0);
        b.setCreatedBy(operatorId);
        try {
            b.setFileSha256(sha256(file.getBytes()));
        } catch (IOException e) {
            throw new BizException("读取上传文件失败：" + e.getMessage());
        }
        batchMapper.insert(b);
        dispatch(b.getId());
        return batchNo;
    }

    /** 派发到线程池；占用 active_flag 失败（同 key 已有在跑）返回 409 提示。 */
    private void dispatch(Long batchId) {
        // 先置处理中（唯一索引兜底同 key 串行）
        int updated = batchMapper.update(new ImportBatch(), new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImportBatch>()
                .eq(ImportBatch::getId, batchId)
                .eq(ImportBatch::getActiveFlag, 0)
                .set(ImportBatch::getActiveFlag, 1)
                .set(ImportBatch::getStatus, ImportStatus.STATUS_PROCESSING));
        if (updated == 0) {
            throw new BizException(409, "该导入类型已有任务在处理中，请稍后再试");
        }
        try {
            importExecutor.execute(() -> runPipeline(batchId));
        } catch (RejectedExecutionException e) {
            // 队列打满：回滚 active_flag，让批次回到可重试状态并提示
            batchMapper.update(new ImportBatch(), new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImportBatch>()
                    .eq(ImportBatch::getId, batchId)
                    .set(ImportBatch::getActiveFlag, 0)
                    .set(ImportBatch::getStatus, ImportStatus.STATUS_QUEUED));
            throw new BizException(503, "导入任务队列已满，请稍后重试");
        }
    }

    /** 后台流水线（在 importExecutor 线程执行） */
    private void runPipeline(Long batchId) {
        ImportContext.enter();
        try {
            ImportBatch b = batchMapper.selectById(batchId);
            ImportContext.setOperatorId(b == null ? null : b.getOperatorId());
            ImportHandler handler = requireHandler(b.getImportKey());

            // ---- PARSING ----
            updatePhase(batchId, ImportStatus.PHASE_PARSING, 5);
            byte[] bytes = readOriginal(b);
            ExcelReadUtil.Parsed parsed;
            try {
                parsed = ExcelReadUtil.parse(bytes, handler.columns());
            } catch (IllegalArgumentException e) {
                fail(batchId, e.getMessage(), List.of(), b);
                return;
            } catch (IOException e) {
                fail(batchId, "Excel 解析失败：" + e.getMessage(), List.of(), b);
                return;
            }
            List<Map<String, String>> rows = parsed.getRows();
            List<Integer> rowNos = parsed.getRowNumbers();
            txStatus.executeWithoutResult(s -> {
                ImportBatch u = new ImportBatch();
                u.setId(batchId);
                u.setTotalRows(rows.size());
                batchMapper.updateById(u);
            });

            // ---- VALIDATING（全量，不写库） ----
            updatePhase(batchId, ImportStatus.PHASE_VALIDATING, 20);
            ImportHandler.ValidateResult vr = handler.validate(rows, b.getOperatorId());
            if (!vr.ok()) {
                Map<Integer, String> errMap = new LinkedHashMap<>();
                List<ImportBatch.ErrorRow> errRows = new ArrayList<>();
                for (ImportHandler.RowError re : vr.getErrors()) {
                    int physical = re.getRowNo() >= 1 && re.getRowNo() <= rowNos.size()
                            ? rowNos.get(re.getRowNo() - 1) : (re.getRowNo() + 1);
                    errMap.put(physical, re.getMessage());
                    ImportBatch.ErrorRow er = new ImportBatch.ErrorRow();
                    er.setRowNo(physical);
                    er.setErrorMsg(re.getMessage());
                    int idx = re.getRowNo() - 1;
                    er.setRawData(idx >= 0 && idx < rows.size() ? new LinkedHashMap<>(rows.get(idx)) : Map.of());
                    errRows.add(er);
                }
                finishFail(batchId, "校验未通过，共 " + errRows.size() + " 行错误，一行未写入", errRows, b);
                return;
            }

            // ---- WRITING（整批事务） ----
            updatePhase(batchId, ImportStatus.PHASE_WRITING, 55);
            int written;
            try {
                written = txWrite.execute(s -> handler.commit(rows, b.getOperatorId(), b.getBatchNo()));
            } catch (Exception e) {
                log.error("[import] 批次 {} 写入失败，整批回滚", b.getBatchNo(), e);
                finishFail(batchId, "写入失败：" + rootMessage(e), List.of(), b);
                return;
            }

            // ---- RECONCILING（期初类） ----
            String reconcileSummary = null;
            boolean partial = false;
            if (handler.needsReconcile()) {
                updatePhase(batchId, ImportStatus.PHASE_RECONCILING, 85);
                ImportHandler.ReconcileResult rr = handler.reconcile(b.getBatchNo());
                if (!rr.isOk()) {
                    partial = true;
                    reconcileSummary = rr.getSummary();
                }
            }

            // ---- 终态 ----
            String finalStatus = partial ? ImportStatus.STATUS_PARTIAL : ImportStatus.STATUS_SUCCESS;
            final boolean isPartial = partial;
            Map<String, Object> ext = new LinkedHashMap<>();
            ext.put("successRows", written);
            if (reconcileSummary != null) ext.put("reconcileSummary", reconcileSummary);
            txStatus.executeWithoutResult(s -> {
                ImportBatch u = new ImportBatch();
                u.setId(batchId);
                u.setPhase(ImportStatus.PHASE_DONE);
                u.setStatus(finalStatus);
                u.setSuccessRows(written);
                u.setFailRows(0);
                u.setProcessedRows(rows.size());
                u.setProgressPct(100);
                u.setActiveFlag(0);
                u.setFinishedAt(LocalDateTime.now());
                u.setExt(ext);
                u.setRemark(isPartial ? "写入成功但对账有差异，请人工核对" : "导入成功");
                batchMapper.updateById(u);
            });
            notifyResult(batchId, handler.displayName(), finalStatus, 0);
            log.info("[import] 批次 {} 完成 status={} rows={}", b.getBatchNo(), finalStatus, written);
        } catch (Exception e) {
            log.error("[import] 批次 {} 流水线异常", batchId, e);
            try {
                ImportBatch b = batchMapper.selectById(batchId);
                finishFail(batchId, "系统异常：" + rootMessage(e), List.of(), b);
            } catch (Exception ex) {
                log.error("[import] 失败摘要写入也失败 batchId={}", batchId, ex);
            }
        } finally {
            ImportContext.clear();
        }
    }

    /** 校验失败（PARSING/VALIDATING 阶段，尚未进入写入） */
    private void fail(Long batchId, String message, List<ImportBatch.ErrorRow> errRows, ImportBatch b) {
        finishFail(batchId, message, errRows, b);
    }

    /** 统一失败终态：释放 active_flag + 写摘要 + 通知 */
    private void finishFail(Long batchId, String message, List<ImportBatch.ErrorRow> errRows, ImportBatch b) {
        Map<String, Object> ext = new LinkedHashMap<>();
        if (!errRows.isEmpty()) ext.put("errors", errRows);
        final int[] failCount = {errRows.size()};
        txStatus.executeWithoutResult(s -> {
            ImportBatch u = new ImportBatch();
            u.setId(batchId);
            u.setPhase(ImportStatus.PHASE_DONE);
            u.setStatus(ImportStatus.STATUS_FAILED);
            u.setFailRows(failCount[0]);
            u.setSuccessRows(0);
            u.setProgressPct(100);
            u.setActiveFlag(0);
            u.setFinishedAt(LocalDateTime.now());
            u.setExt(ext);
            u.setRemark(message.length() > 500 ? message.substring(0, 500) : message);
            batchMapper.updateById(u);
        });
        notifyResult(batchId, b != null ? requireHandler(b.getImportKey()).displayName() : "数据导入",
                ImportStatus.STATUS_FAILED, failCount[0]);
    }

    private void updatePhase(Long batchId, String phase, int pct) {
        txStatus.executeWithoutResult(s -> {
            ImportBatch u = new ImportBatch();
            u.setId(batchId);
            u.setPhase(phase);
            u.setProgressPct(pct);
            if (ImportStatus.PHASE_PARSING.equals(phase)) u.setStartedAt(LocalDateTime.now());
            batchMapper.updateById(u);
        });
    }

    /** 读原始文件字节（从 FileStorage） */
    private byte[] readOriginal(ImportBatch b) {
        if (b.getFileStorageId() == null) throw new BizException("批次缺少原始文件");
        FileStorage fs = fileStorageMapper.selectById(b.getFileStorageId());
        if (fs == null) throw new BizException("原始文件记录不存在");
        try (InputStream in = storage.read(fs.getStoragePath())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new BizException("读取原始文件失败：" + e.getMessage());
        }
    }

    /** 完成通知（dedup_key 防重） */
    private void notifyResult(Long batchId, String importName, String status, int failRows) {
        ImportBatch b = batchMapper.selectById(batchId);
        if (b == null || b.getNotifySent() != null && b.getNotifySent() == 1) return;
        String result = ImportStatus.STATUS_SUCCESS.equals(status) ? "成功"
                : ImportStatus.STATUS_PARTIAL.equals(status) ? "部分成功" : "失败";
        try {
            NotifyCommand cmd = new NotifyCommand();
            cmd.setCode("IMPORT_RESULT");
            cmd.setReceivers(b.getOperatorId() == null ? List.of() : List.of(b.getOperatorId()));
            cmd.setBizType("IMPORT");
            cmd.setBizId(String.valueOf(batchId));
            cmd.setLinkUrl("/import-history");
            cmd.setDedupKey("import:" + b.getBatchNo());
            cmd.setVars(Map.of(
                    "batchNo", b.getBatchNo(),
                    "importName", importName,
                    "result", result,
                    "failRows", String.valueOf(failRows)));
            cmd.setOperatorId(b.getOperatorId());
            notificationService.notify(cmd);
            txStatus.executeWithoutResult(s -> {
                ImportBatch u = new ImportBatch();
                u.setId(batchId);
                u.setNotifySent(1);
                batchMapper.updateById(u);
            });
        } catch (Exception e) {
            log.warn("[import] 完成通知发送失败 batchId={}（不影响导入结果）: {}", batchId, e.getMessage());
        }
    }

    @Override
    public ImportBatch detail(Long id) {
        ImportBatch b = batchMapper.selectById(id);
        if (b == null) throw new BizException("批次不存在");
        return b;
    }

    @Override
    public List<ImportBatch> history(String importKey, Long operatorId, Integer limit) {
        LambdaQueryWrapper<ImportBatch> q = new LambdaQueryWrapper<ImportBatch>()
                .orderByDesc(ImportBatch::getCreatedAt);
        if (importKey != null && !importKey.isBlank()) q.eq(ImportBatch::getImportKey, importKey);
        if (operatorId != null) q.eq(ImportBatch::getOperatorId, operatorId);
        int safe = (limit == null || limit <= 0) ? 50 : Math.min(limit, 200);
        q.last("LIMIT " + safe);
        return batchMapper.selectList(q);
    }

    @Override
    public byte[] errorWorkbook(Long id) {
        ImportBatch b = detail(id);
        if (!ImportStatus.STATUS_FAILED.equals(b.getStatus())) {
            throw new BizException("只有失败批次才能下载错误行");
        }
        ImportHandler handler = requireHandler(b.getImportKey());
        byte[] original = readOriginal(b);
        Map<Integer, String> errMap = new LinkedHashMap<>();
        if (b.getExt() != null && b.getExt().get("errors") instanceof List<?> errs) {
            for (Object o : errs) {
                if (o instanceof Map<?, ?> m) {
                    Object rn = m.get("rowNo");
                    Object em = m.get("errorMsg");
                    if (rn instanceof Number n && em != null) errMap.put(n.intValue(), String.valueOf(em));
                }
            }
        }
        try {
            return ExcelTemplateUtil.errorBook(original, handler.columns(), errMap, b.getBatchNo());
        } catch (IOException e) {
            throw new BizException("生成错误文件失败：" + e.getMessage());
        }
    }

    @Override
    public byte[] template(String key) {
        ImportHandler handler = requireHandler(key);
        try {
            return ExcelTemplateUtil.template(handler.sheetName(), handler.columns(), handler.notes());
        } catch (IOException e) {
            throw new BizException("生成模板失败：" + e.getMessage());
        }
    }

    @Override
    public void archive(Long id, Long operatorId) {
        ImportBatch b = detail(id);
        if (!ImportStatus.isTerminal(b.getStatus())) {
            throw new BizException("只有已完成的批次才能归档");
        }
        ImportBatch u = new ImportBatch();
        u.setId(id);
        u.setStatus(ImportStatus.STATUS_ARCHIVED);
        u.setUpdatedBy(operatorId);
        batchMapper.updateById(u);
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("SHA-256 计算失败", e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null || m.isBlank() ? cur.getClass().getSimpleName() : m;
    }
}
