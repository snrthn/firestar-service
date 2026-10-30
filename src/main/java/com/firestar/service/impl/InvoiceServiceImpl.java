package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.annotation.Auditable;
import com.firestar.common.BatchLoader;
import com.firestar.common.DataScopeHelper;
import com.firestar.common.FlowNodeUtil;
import com.firestar.dto.AllocationItem;
import com.firestar.dto.AllocationVO;
import com.firestar.dto.InvoiceRequest;
import com.firestar.dto.InvoiceVO;
import com.firestar.dto.NotifyCommand;
import com.firestar.dto.TaskInfo;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.entity.DictData;
import com.firestar.entity.FileStorage;
import com.firestar.entity.Invoice;
import com.firestar.entity.InvoiceAllocation;
import com.firestar.entity.ProcessDef;
import com.firestar.entity.Project;
import com.firestar.entity.Receivable;
import com.firestar.entity.Role;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.mapper.DictDataMapper;
import com.firestar.mapper.FileStorageMapper;
import com.firestar.mapper.ProcessDefMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.InvoiceMapper;
import com.firestar.mapper.InvoiceAllocationMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.NotificationService;
import com.firestar.service.InvoiceService;
import com.firestar.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 发票（开票）业务逻辑实现（与立项/发货/收款一致走 Flowable 审批流）。
 *
 * <p>流程码 {@code invoice_approval}，审批节点角色 {@code ROLE_INVOICER}（流程定义 + 角色授权
 * 已在迁移 V60 种好，由 ProcessDefAutoDeployRunner 自动部署，业务层只管 engine.start 拉起）。</p>
 *
 * <p>金额纪律：一律 BigDecimal，null 当 0，用 add 不用 +=。</p>
 */
@Slf4j
@Service
public class InvoiceServiceImpl implements InvoiceService {

    /** 发票审批流程 process_code */
    public static final String INVOICE_PROCESS_CODE = "invoice_approval";

    /** 审批节点候选角色（开票员） */
    private static final String ROLE_INVOICER = "ROLE_INVOICER";

    private static final String DICT_INVOICE_STATUS = "approval_status";
    private static final String DICT_INVOICE_TYPE = "invoice_type";
    private static final String DICT_DOC_DIRECTION = "doc_direction";

    /** 核销单号日期格式（IA-yyyyMMdd-NNN，同 ReceiptServiceImpl.generateReceiptNo 范式） */
    private static final DateTimeFormatter ALLOCATION_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 附件软关联业务类型（2026-10-25 V62 真上传）。
     *
     * <p>字面量必须与 file_biz_type 字典的 dict_value 严格一致（V20/V54 口径），
     * 否则该文件在文件管理页不可见、不可筛。</p>
     */
    public static final String INVOICE_ATTACH_BIZ_TYPE = "invoice";

    private final InvoiceMapper invoiceMapper;
    private final FileStorageMapper fileStorageMapper;
    private final ReceivableMapper receivableMapper;
    private final ProjectMapper projectMapper;
    private final DictDataMapper dictDataMapper;
    private final ProcessDefMapper processDefMapper;
    private final WorkflowEngineService engine;
    private final NotificationService notificationService;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final InvoiceAllocationMapper invoiceAllocationMapper;

    @Autowired
    public InvoiceServiceImpl(InvoiceMapper invoiceMapper, FileStorageMapper fileStorageMapper,
                             ReceivableMapper receivableMapper,
                             ProjectMapper projectMapper, DictDataMapper dictDataMapper,
                             ProcessDefMapper processDefMapper, WorkflowEngineService engine,
                             NotificationService notificationService, UserMapper userMapper,
                             RoleMapper roleMapper, UserRoleMapper userRoleMapper,
                             InvoiceAllocationMapper invoiceAllocationMapper) {
        this.invoiceMapper = invoiceMapper;
        this.fileStorageMapper = fileStorageMapper;
        this.receivableMapper = receivableMapper;
        this.projectMapper = projectMapper;
        this.dictDataMapper = dictDataMapper;
        this.processDefMapper = processDefMapper;
        this.engine = engine;
        this.notificationService = notificationService;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.invoiceAllocationMapper = invoiceAllocationMapper;
    }

    // ===================== 创建（草稿，不拉审批） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Auditable(operationType = "CREATE", targetType = "INVOICE", targetIdSpEl = "#result",
            entityClass = Invoice.class,
            fields = {"projectId", "receivableId", "invoiceAmount", "taxRate", "taxAmount",
                    "invoiceType", "invoiceDate", "docDirection"})
    public Long create(InvoiceRequest req) {
        Long operatorId = currentUserId();
        // V65 双向拆分：allocations 非空时以第一行为主应收单（主表列 NOT NULL）；
        // 空时沿用旧请求体的 receivableId（单张开票，兼容历史前端）
        Long mainReceivableId = req.getAllocations() != null && !req.getAllocations().isEmpty()
                ? req.getAllocations().get(0).getReceivableId() : req.getReceivableId();
        if (mainReceivableId == null) {
            throw new IllegalArgumentException("应收单不能为空");
        }
        Receivable receivable = receivableMapper.selectById(mainReceivableId);
        if (receivable == null) {
            throw new IllegalArgumentException("应收单不存在：" + mainReceivableId);
        }
        if (req.getProjectId() == null) {
            throw new IllegalArgumentException("项目不能为空");
        }
        Project project = projectMapper.selectById(req.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("项目不存在：" + req.getProjectId());
        }
        if (!Objects.equals(receivable.getProjectId(), project.getId())) {
            throw new IllegalArgumentException("应收单不属于该项目：" + req.getReceivableId());
        }
        // 方案A（2026-10-27）：总额强制 = Σ分配（只读自动算）；兼容旧 receivableId 单张开票路径兜底
        BigDecimal total = (req.getAllocations() != null && !req.getAllocations().isEmpty())
                ? req.getAllocations().stream()
                .map(a -> a.getAmount() == null ? BigDecimal.ZERO : a.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                : req.getInvoiceAmount();
        if (total == null || total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("开票金额必须大于 0");
        }
        if (req.getInvoiceDate() == null) {
            throw new IllegalArgumentException("开票日期不能为空");
        }
        // 发票文件附件改为选填（2026-10-31 虎哥拍板）：开票申请阶段常常还没拿到票面扫描件，
        // 强制必传会卡住流程；附件可在后续环节补传。空列表直接跳过 bindAttachments，不报错。
        if (req.getInvoiceType() == null || req.getInvoiceType().isBlank()) {
            throw new IllegalArgumentException("发票类型不能为空");
        }

        Invoice invoice = new Invoice();
        invoice.setProjectId(project.getId());
        invoice.setReceivableId(receivable.getId());
        invoice.setInvoiceAmount(total);
        invoice.setTaxRate(req.getTaxRate());
        invoice.setTaxAmount(taxOf(req.getInvoiceAmount(), req.getTaxRate()));
        // 业务单号：未手填时系统自动生成 FP-yyyyMMdd-NNN（MAX+1 含逻辑删除占位，不复用已删号）；
        // 手填场景 = 录纸质发票票号，必须业务层查重（含逻辑删除行）防唯一键冲突 500（2026-10-27 实锤）
        String invoiceNo = (req.getInvoiceNo() == null || req.getInvoiceNo().isBlank())
                ? generateInvoiceNo() : req.getInvoiceNo();
        if (req.getInvoiceNo() != null && !req.getInvoiceNo().isBlank()
                && invoiceMapper.selectIdByInvoiceNo(invoiceNo, null) != null) {
            throw new IllegalArgumentException("发票号码已存在：" + invoiceNo
                    + "，请更换或留空由系统自动生成");
        }
        invoice.setInvoiceNo(invoiceNo);
        invoice.setInvoiceCode(req.getInvoiceCode());
        invoice.setInvoiceType(req.getInvoiceType());
        invoice.setInvoiceDate(req.getInvoiceDate());
        // invoice_file_path 已废弃（V62 真上传），附件走软关联，落库前不再写该列
        // 单据方向：默认 BLUE（蓝字）；RED（红字）= 红冲单，须挂已审批通过的原单
        // （2026-10-25 红冲入口：正数金额+方向标记，汇总/核销时按方向代数运算）
        String direction = "RED".equals(req.getDocDirection()) ? "RED" : "BLUE";
        invoice.setDocDirection(direction);
        if ("RED".equals(direction)) {
            invoice.setOriginalInvoiceId(validateRedFlush(req, project).getId());
        }
        // 创建即草稿，待提交再拉审批
        invoice.setApprovalStatus("DRAFT");
        // 归属人 = 当前用户（申请人）
        invoice.setApplicantId(operatorId);
        invoice.setClerkId(null);
        invoiceMapper.insert(invoice);
        // 附件软关联挂载：前端新建时单据尚无 id，上传未带 bizId，此处拿到自增 id 后统一挂载
        bindAttachments(invoice.getId(), req.getAttachmentFileIds());
        // 核销分配明细落库（V65 双向拆分）：计划态不滚数字，completeTask 审批通过才生效
        // 传主应收+方向：空分配（旧兼容路径）也要过"未开票余额"闸；红冲单（RED）不受上限限制
        saveAllocations(invoice.getId(), req.getAllocations(), total, project.getId(),
                receivable.getId(), direction);

        log.info("[invoice] 创建发票草稿 id={} projectId={} receivableId={} amount={} allocations={}",
                invoice.getId(), project.getId(), receivable.getId(), req.getInvoiceAmount(),
                req.getAllocations() == null ? 0 : req.getAllocations().size());
        return invoice.getId();
    }

    // ===================== 提交审批 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Auditable(operationType = "UPDATE", targetType = "INVOICE", targetIdSpEl = "#id",
            entityClass = Invoice.class, fields = {"approvalStatus", "processInstanceId"})
    public void submit(Long id) {
        Invoice r = invoiceMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("发票不存在：" + id);
        }
        // 草稿态 + 已驳回态可提交（2026-10-25 抄用友作业：驳回=回到编写中，可改后重提）
        if (!"DRAFT".equals(r.getApprovalStatus()) && !"REJECTED".equals(r.getApprovalStatus())) {
            throw new IllegalArgumentException("仅草稿态或已驳回态可提交审批，当前：" + r.getApprovalStatus());
        }
        ProcessDef def = processDefMapper.selectOne(new LambdaQueryWrapper<ProcessDef>()
                .eq(ProcessDef::getProcessCode, INVOICE_PROCESS_CODE)
                .eq(ProcessDef::getStatus, "ENABLED"));
        if (def == null) {
            throw new IllegalArgumentException("发票审批流程未配置或已停用：" + INVOICE_PROCESS_CODE);
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("applicantUsername", currentUsername());
        vars.put("applicantId", currentUserId());
        String instanceId = engine.start(INVOICE_PROCESS_CODE, String.valueOf(id), vars);
        r.setProcessInstanceId(instanceId);
        r.setApprovalStatus("APPROVING");
        invoiceMapper.updateById(r);

        log.info("[invoice] 提交发票审批 id={} instanceId={} applicant={}", id, instanceId, currentUsername());
        // 提交通知：通知开票员（ROLE_INVOICER）
        notifySubmitted(r, "INVOICE_DONE");
    }

    // ===================== 编辑（仅 PENDING 态 / 本人+admin） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Auditable(operationType = "UPDATE", targetType = "INVOICE", targetIdSpEl = "#id",
            entityClass = Invoice.class, fields = {"invoiceAmount", "taxRate", "taxAmount",
                    "invoiceNo", "invoiceCode", "invoiceType", "invoiceDate", "invoiceFilePath"})
    public void update(Long id, InvoiceRequest req, String username, boolean isAdmin) {
        Long operatorId = currentUserId();
        Invoice r = invoiceMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("发票不存在：" + id);
        }
        // 归属校验：写类仅申请人本人（applicantId）或 ROLE_ADMIN
        if (!isAdmin && !Objects.equals(r.getApplicantId(), operatorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发票不存在或无权操作：" + id);
        }
        // 草稿态 + 已驳回态可编辑（用友范式：驳回=回到编写中；ISSUING 在审批链中不可改）
        if (!"DRAFT".equals(r.getApprovalStatus()) && !"REJECTED".equals(r.getApprovalStatus())) {
            throw new IllegalArgumentException("仅草稿态或已驳回态可编辑，当前：" + r.getApprovalStatus());
        }
        if (req.getInvoiceAmount() == null || req.getInvoiceAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("开票金额必须为正");
        }
        if (req.getInvoiceDate() == null) {
            throw new IllegalArgumentException("开票日期不能为空");
        }
        // 附件选填（同 create 口径）：空列表走 rebindAttachments 的差分解绑，即"清空附件"，不报错。
        r.setInvoiceAmount(req.getInvoiceAmount());
        r.setTaxRate(req.getTaxRate());
        r.setTaxAmount(taxOf(req.getInvoiceAmount(), req.getTaxRate()));
        // 发票号查重（排除自身）：手填重复票号会撞 uk_tenant_invoice_no 唯一键 -> 500，提前拦截
        String newInvoiceNo = req.getInvoiceNo();
        if (newInvoiceNo != null && !newInvoiceNo.isBlank()
                && !Objects.equals(newInvoiceNo, r.getInvoiceNo())
                && invoiceMapper.selectIdByInvoiceNo(newInvoiceNo, id) != null) {
            throw new IllegalArgumentException("发票号码已存在：" + newInvoiceNo
                    + "，请更换或留空由系统自动生成");
        }
        r.setInvoiceNo(newInvoiceNo);
        r.setInvoiceCode(req.getInvoiceCode());
        r.setInvoiceType(req.getInvoiceType());
        r.setInvoiceDate(req.getInvoiceDate());
        // invoice_file_path 已废弃（V62 真上传），不再写该列
        r.setUpdatedBy(operatorId);
        invoiceMapper.updateById(r);
        // 附件以本次提交的全量列表为准：新上传的挂上，前端删掉的会被解绑（bizId 置 NULL）
        rebindAttachments(id, req.getAttachmentFileIds());
        // V65：主应收单锁定不可换（主表列语义 = allocations 首行；换单等于换合同，应新建）
        if (req.getAllocations() != null && !req.getAllocations().isEmpty()
                && !Objects.equals(req.getAllocations().get(0).getReceivableId(), r.getReceivableId())) {
            throw new IllegalArgumentException("主应收单不可更换，请新建发票");
        }
        // 分配明细同口径全量替换：计划态未生效，硬删重插无痕（V65）
        invoiceAllocationMapper.delete(new LambdaQueryWrapper<InvoiceAllocation>()
                .eq(InvoiceAllocation::getInvoiceId, id));
        // 方案A（2026-10-27）：编辑态发票金额法定锁定，总额以原单金额为准，
        // 校验各分配行之和 = 原发票金额（防止编辑分配时偏离法定金额）
        BigDecimal total = r.getInvoiceAmount();
        // 同 create：空分配也要过"未开票余额"闸；方向以库中原单为准（编辑请求不带 docDirection）
        saveAllocations(id, req.getAllocations(), total, r.getProjectId(),
                r.getReceivableId(), r.getDocDirection());
        log.info("[invoice] 编辑发票 id={} amount={} allocations={} user={}",
                id, total, req.getAllocations() == null ? 0 : req.getAllocations().size(),
                currentUsername());
    }

    // ===================== 撤回（仅本人+admin） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Auditable(operationType = "UPDATE", targetType = "INVOICE", targetIdSpEl = "#id",
            entityClass = Invoice.class, fields = {"approvalStatus"})
    public void withdraw(Long id, String username, boolean isAdmin) {
        Invoice r = invoiceMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("发票不存在：" + id);
        }
        if (!isAdmin && !Objects.equals(r.getApplicantId(), currentUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发票不存在或无权操作：" + id);
        }
        if (r.getProcessInstanceId() == null || r.getProcessInstanceId().isBlank()) {
            throw new IllegalArgumentException("该发票没有进行中的审批流程，无需撤回");
        }
        List<Long> approvedUserIds = engine.withdraw(r.getProcessInstanceId(), currentUsername(),
                WorkflowEngineService.WITHDRAW_REASON_PREFIX + "发票申请，发票 id=" + id);
        r.setApprovalStatus("CANCELLED");
        r.setUpdatedBy(currentUserId());
        invoiceMapper.updateById(r);
        // 分配明细硬删（V65）：withdraw 只可能发生在审批中（引擎挡已结束流程），
        // 此时 completeTask 尚未跑过、应收单/项目数字未滚 → 删计划行即守恒，无需反核销
        invoiceAllocationMapper.delete(new LambdaQueryWrapper<InvoiceAllocation>()
                .eq(InvoiceAllocation::getInvoiceId, id));
        log.info("[invoice] 申请人撤回 id={} user={} 已审批人={}", id, currentUsername(), approvedUserIds);
        notifyWithdrawn(r, approvedUserIds);
    }

    // ===================== 审批通过回调 =====================

    @Override
    @Auditable(operationType = "APPROVE", targetType = "APPROVAL", targetIdSpEl = "#taskId",
            remark = "发票审批通过")
    public void completeTask(String taskId, Map<String, Object> variables) {
        String comment = variables == null ? null : Objects.toString(variables.get("comment"), null);
        if (comment != null && comment.isBlank()) {
            comment = null;
        }
        // 时序双保险：complete 前先拿 businessKey（参考 ProjectServiceImpl.completeTask）
        String bizKeyUpfront = engine.businessKeyOfTask(taskId);
        String instanceId = engine.complete(taskId, variables, comment);
        List<TaskInfo> next = engine.listTasks(instanceId);
        if (next.isEmpty()) {
            String bizKey = bizKeyUpfront != null ? bizKeyUpfront : engine.getBusinessKey(instanceId);
            Invoice r = (bizKey != null) ? invoiceMapper.selectById(Long.parseLong(bizKey)) : null;
            if (r != null) {
                r.setApprovalStatus("APPROVED");
                // 开票员 = 当前操作人（审批通过人）
                r.setClerkId(currentUserId());
                r.setUpdatedBy(currentUserId());
                invoiceMapper.updateById(r);
                // 累加 fin_project.invoiced_amount（金额纪律：null 当 0，add）
                accumulateProjectInvoiced(r.getProjectId(), r.getInvoiceAmount(), r.getDocDirection());
                // 核销生效（V65）：按分配明细滚各应收单已开票/未开票
                applyInvoiceAllocations(r);
                log.info("[invoice] 审批通过回写 id={} amount={}", r.getId(), r.getInvoiceAmount());
                notifyResult(r, "INVOICE_DONE", "已开具", null);
            } else {
                log.warn("[invoice] 审批通过但未回写：bizKey={} 找不到发票 instanceId={}", bizKey, instanceId);
            }
        }
    }

    // ===================== 审批驳回 =====================

    @Override
    @Auditable(operationType = "REJECT", targetType = "APPROVAL", targetIdSpEl = "#taskId",
            remark = "发票审批驳回")
    public void rejectTask(String taskId, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("驳回理由不能为空");
        }
        engine.assertTaskHandleable(taskId);
        String bizKey = engine.businessKeyOfTask(taskId);
        if (bizKey == null) {
            log.warn("[invoice] 驳回时取不到业务键（任务可能已结束）taskId={}", taskId);
            return;
        }
        Invoice r = invoiceMapper.selectById(Long.parseLong(bizKey));
        if (r == null) {
            log.warn("[invoice] 驳回时找不到发票 bizKey={} taskId={}", bizKey, taskId);
            return;
        }
        if (r.getProcessInstanceId() != null) {
            engine.terminate(r.getProcessInstanceId(), "发票申请被退回：" + comment);
        }
        r.setApprovalStatus("REJECTED");
        r.setUpdatedBy(currentUserId());
        invoiceMapper.updateById(r);
        log.info("[invoice] 审批驳回回写 id={} comment={}", r.getId(), comment);
        notifyResult(r, "INVOICE_DONE", "已退回", comment);
    }

    // ===================== 查询 =====================

    @Override
    public IPage<InvoiceVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                                 Long projectId, Long receivableId, String status, String invoiceNo,
                                 String projectName, String invoiceType, String docDirection,
                                 LocalDate dateFrom, LocalDate dateTo) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 500L);
        String statusEq = status == null || status.isBlank() ? null : status.trim();
        String noKw = invoiceNo == null || invoiceNo.isBlank() ? null : invoiceNo.trim();
        String nameKw = projectName == null || projectName.isBlank() ? null : projectName.trim();
        String typeEq = invoiceType == null || invoiceType.isBlank() ? null : invoiceType.trim();
        String dirEq = docDirection == null || docDirection.isBlank() ? null : docDirection.trim();

        // 项目名称模糊 → projectId 集合（两段式，与 ShipmentServiceImpl 同范式，不拼 SQL 无注入）
        List<Long> nameIds = null;
        if (nameKw != null) {
            nameIds = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                            .like(Project::getProjectName, nameKw).select(Project::getId))
                    .stream().map(Project::getId).filter(Objects::nonNull).toList();
            if (nameIds.isEmpty()) {
                return new Page<>(current, size, 0);
            }
        }

        Long me = currentUserId();
        if (DataScopeHelper.denyAll(me)) {
            return new Page<>(current, size, 0);
        }
        IPage<Invoice> page = invoiceMapper.selectPage(new Page<>(current, size),
                DataScopeHelper.own(
                        new LambdaQueryWrapper<Invoice>()
                                .eq(projectId != null, Invoice::getProjectId, projectId)
                                .eq(receivableId != null, Invoice::getReceivableId, receivableId)
                                .eq(statusEq != null, Invoice::getApprovalStatus, statusEq)
                                .eq(typeEq != null, Invoice::getInvoiceType, typeEq)
                                .eq(dirEq != null, Invoice::getDocDirection, dirEq)
                                .like(noKw != null, Invoice::getInvoiceNo, noKw)
                                .in(nameIds != null, Invoice::getProjectId, nameIds)
                                .ge(dateFrom != null, Invoice::getInvoiceDate, dateFrom)
                                .le(dateTo != null, Invoice::getInvoiceDate, dateTo)
                                .orderByDesc(Invoice::getCreatedAt),
                        Invoice::getApplicantId, me));
        Map<String, DictData> statusDict = dictMap(DICT_INVOICE_STATUS);
        Map<String, DictData> typeDict = dictMap(DICT_INVOICE_TYPE);
        Map<String, DictData> docDict = dictMap(DICT_DOC_DIRECTION);
        Map<Long, String> userNames = userNamesOf(page.getRecords());
        // 项目 / 应收单批量预取：一页最多 500 条，逐条 selectById 会放大成 1000 次查询
        Map<Long, Project> projects =
                BatchLoader.projects(projectMapper, page.getRecords(), Invoice::getProjectId);
        Map<Long, Receivable> receivables =
                BatchLoader.receivables(receivableMapper, page.getRecords(), Invoice::getReceivableId);
        return page.convert(r -> toVO(r, statusDict, typeDict, docDict, userNames, projects, receivables));
    }

    @Override
    public InvoiceVO detail(Long id, String username, boolean isAdmin) {
        Invoice r = invoiceMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("发票不存在：" + id);
        }
        // 可见性三选一：申请人本人（applicantId）/ ROLE_ADMIN / 审批任务处理人（候选人）
        if (!isAdmin && !Objects.equals(r.getApplicantId(), currentUserId())
                && !engine.isCandidate(INVOICE_PROCESS_CODE, String.valueOf(id), username)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发票不存在或无权访问：" + id);
        }
        Map<String, DictData> statusDict = dictMap(DICT_INVOICE_STATUS);
        Map<String, DictData> typeDict = dictMap(DICT_INVOICE_TYPE);
        Map<String, DictData> docDict = dictMap(DICT_DOC_DIRECTION);
        Map<Long, String> userNames = userNamesOf(List.of(r));
        Map<Long, Project> projects =
                BatchLoader.projects(projectMapper, List.of(r), Invoice::getProjectId);
        Map<Long, Receivable> receivables =
                BatchLoader.receivables(receivableMapper, List.of(r), Invoice::getReceivableId);
        InvoiceVO vo = toVO(r, statusDict, typeDict, docDict, userNames, projects, receivables);
        // 附件 ID 仅详情回填：放在 toVO 里会被分页放大成 N+1（一页最多 500 次单条查询）
        vo.setAttachmentFileIds(loadAttachmentIds(id));
        // 核销分配明细仅详情回填（同附件口径，V65）
        vo.setAllocations(allocationVOs(id));
        // 红冲原单号仅详情回填（N+1 防护同附件口径）
        if (r.getOriginalInvoiceId() != null) {
            Invoice original = invoiceMapper.selectById(r.getOriginalInvoiceId());
            if (original != null) {
                vo.setOriginalInvoiceNo(original.getInvoiceNo());
            }
        }
        return vo;
    }

    @Override
    public List<TimelineNodeVO> timeline(Long id) {
        // processKey 必传：businessKey 跨表相撞（发票 #2 = 收款单 #2），不带类型过滤会串台
        return engine.listTimelineByBusinessKey(INVOICE_PROCESS_CODE, String.valueOf(id));
    }

    // ===================== 私有 =====================

    /** 累加项目已开票（fin_project.invoiced_amount）；金额纪律：null 当 0，add */
    /**
     * 累加/扣减项目已开票（fin_project.invoiced_amount）；金额纪律：null 当 0。
     *
     * <p>docDirection=RED（红冲单）时做减法，保持冗余字段守恒可对账
     * （2026-10-25 红冲入口；实时聚合口径在 ProjectServiceImpl，此处仅维护冗余列）。</p>
     */
    private void accumulateProjectInvoiced(Long projectId, BigDecimal amount, String docDirection) {
        if (projectId == null || amount == null) {
            return;
        }
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            return;
        }
        boolean red = "RED".equals(docDirection);
        BigDecimal base = p.getInvoicedAmount() == null ? BigDecimal.ZERO : p.getInvoicedAmount();
        p.setInvoicedAmount(red ? base.subtract(amount) : base.add(amount));
        projectMapper.updateById(p);
    }

    /**
     * 红冲单校验（2026-10-25 红冲入口）：原单必须存在、已审批通过（ISSUED）、
     * 同项目、本单金额 ≤ 原单金额（支持部分红冲，税务同口径）。
     */
    private Invoice validateRedFlush(InvoiceRequest req, Project project) {
        if (req.getOriginalInvoiceId() == null) {
            throw new IllegalArgumentException("红冲单必须指定原发票（originalInvoiceId）");
        }
        Invoice original = invoiceMapper.selectById(req.getOriginalInvoiceId());
        if (original == null) {
            throw new IllegalArgumentException("原发票不存在：" + req.getOriginalInvoiceId());
        }
        if (!"APPROVED".equals(original.getApprovalStatus())) {
            throw new IllegalArgumentException("仅已完成开票（审批通过）的发票可红冲，原单当前状态：" + original.getApprovalStatus());
        }
        if (!Objects.equals(original.getProjectId(), project.getId())) {
            throw new IllegalArgumentException("红冲单必须与原单同项目，请新建");
        }
        if (req.getInvoiceAmount().compareTo(original.getInvoiceAmount()) > 0) {
            throw new IllegalArgumentException("红冲金额不能超过原单金额（" + original.getInvoiceAmount().toPlainString() + "），支持部分红冲");
        }
        return original;
    }

    // ===================== 核销分配（V65 双向拆分，虎哥拍板 B） =====================

    /**
     * 校验并落库开票分配明细（计划态，不滚数字）。
     *
     * <p>规则：① Σ amount = 主单总额；② 每行 ≤ 该应收单未开票余额扣减在途分配
     * （审批中其他发票占用；已通过单的额度已滚进 uninvoiced_amount，不重复扣）；
     * ③ 同一应收单合并一行；④ 分配必须落在本项目下。空列表兜底为主应收单独享
     * （不插明细，兼容 V65 前历史数据）。</p>
     */
    private void saveAllocations(Long invoiceId, List<AllocationItem> items, BigDecimal total,
                                 Long projectId, Long mainReceivableId, String docDirection) {
        // V77：票款勾稽开关（false=不允许回款前开票时强制 开票额≤已回款−已开票）
        Project proj = projectMapper.selectById(projectId);
        boolean allowInvoiceBeforeReceipt = proj != null
                && !Boolean.FALSE.equals(proj.getAllowInvoiceBeforeReceipt());
        if (items == null || items.isEmpty()) {
            // 旧兼容路径（无分配明细 = 主应收单独享全额）同样必须过"未开票余额"闸，
            // 否则任意大金额可绕过全部校验直接落库（2026-10-27 实锤：id=7 开出 55,555,555.00）。
            // 红冲单（RED）审批通过后是加回额度（uninvoiced.add），不受上限限制。
            if (!"RED".equals(docDirection)) {
                assertWithinUninvoiced(mainReceivableId, total, invoiceId);
                // 票款勾稽（V77）：不允许回款前开票时，主应收单开票额不得超「已回款−已开票」
                if (!allowInvoiceBeforeReceipt) {
                    Receivable mrv = receivableMapper.selectById(mainReceivableId);
                    BigDecimal received = (mrv == null || mrv.getReceivedAmount() == null)
                            ? BigDecimal.ZERO : mrv.getReceivedAmount();
                    BigDecimal invoiced = (mrv == null || mrv.getInvoicedAmount() == null)
                            ? BigDecimal.ZERO : mrv.getInvoicedAmount();
                    BigDecimal ceiling = received.subtract(invoiced);
                    if (total.compareTo(ceiling) > 0) {
                        throw new IllegalArgumentException("项目已设为「不允许回款前开票」，应收单 "
                                + (mrv == null ? String.valueOf(mainReceivableId) : mrv.getReceivableNo())
                                + " 本次开票 " + total.toPlainString() + " 超过可开票余额 " + ceiling.toPlainString()
                                + "（已回款 " + received.toPlainString() + " − 已开票 " + invoiced.toPlainString() + "）");
                    }
                }
            }
            return;
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (AllocationItem it : items) {
            if (it.getReceivableId() == null) {
                throw new IllegalArgumentException("分配明细缺少应收单");
            }
            if (!ids.add(it.getReceivableId())) {
                throw new IllegalArgumentException("同一应收单重复分配，请合并为一行：" + it.getReceivableId());
            }
        }
        List<Receivable> receivables = receivableMapper.selectList(new LambdaQueryWrapper<Receivable>()
                .in(Receivable::getId, ids));
        if (receivables.size() != ids.size()) {
            throw new IllegalArgumentException("分配明细中包含不存在的应收单");
        }
        Map<Long, Receivable> byId = receivables.stream()
                .collect(Collectors.toMap(Receivable::getId, rv -> rv));
        BigDecimal sum = BigDecimal.ZERO;
        boolean red = "RED".equals(docDirection);
        for (AllocationItem it : items) {
            Receivable rv = byId.get(it.getReceivableId());
            if (!Objects.equals(rv.getProjectId(), projectId)) {
                throw new IllegalArgumentException("应收单不属于本项目：" + rv.getReceivableNo());
            }
            if (it.getAmount() == null || it.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("分配金额必须为正：" + rv.getReceivableNo());
            }
            // 蓝字：分配 ≤ 未开票余额 − 其他发票在途占用；红冲（RED）是加回额度，不拦
            if (!red) {
                BigDecimal available = (rv.getUninvoicedAmount() == null ? BigDecimal.ZERO : rv.getUninvoicedAmount())
                        .subtract(sumInFlightAllocations(rv.getId(), invoiceId));
                if (it.getAmount().compareTo(available) > 0) {
                    throw new IllegalArgumentException("超出应收单未开票余额：" + rv.getReceivableNo()
                            + "，可用 " + available.toPlainString());
                }
                // 票款勾稽（V77）：不允许回款前开票时，开票额不得超「已回款−已开票」余额
                BigDecimal received = rv.getReceivedAmount() == null ? BigDecimal.ZERO : rv.getReceivedAmount();
                BigDecimal invoiced = rv.getInvoicedAmount() == null ? BigDecimal.ZERO : rv.getInvoicedAmount();
                BigDecimal ceiling = received.subtract(invoiced);
                if (!allowInvoiceBeforeReceipt && it.getAmount().compareTo(ceiling) > 0) {
                    throw new IllegalArgumentException("项目已设为「不允许回款前开票」，应收单 " + rv.getReceivableNo()
                            + " 本次开票 " + it.getAmount().toPlainString() + " 超过可开票余额 " + ceiling.toPlainString()
                            + "（已回款 " + received.toPlainString() + " − 已开票 " + invoiced.toPlainString() + "）");
                }
            }
            sum = sum.add(it.getAmount());
        }
        if (sum.compareTo(total) != 0) {
            throw new IllegalArgumentException("分配总额必须等于开票金额：分配 " + sum.toPlainString()
                    + " ≠ 开票 " + total.toPlainString());
        }
        for (AllocationItem it : items) {
            InvoiceAllocation a = new InvoiceAllocation();
            a.setAllocationNo(generateAllocationNo());
            a.setInvoiceId(invoiceId);
            a.setReceivableId(it.getReceivableId());
            a.setAmount(it.getAmount());
            a.setStatus("NORMAL");
            invoiceAllocationMapper.insert(a);
        }
        log.info("[invoice] 开票分配落库 invoiceId={} rows={} total={}", invoiceId, items.size(), total);
    }

    /** 税额 = 金额 × 税率（V77：前端只读自动算，后端以金额×税率重算，不信任前端传入值） */
    private static BigDecimal taxOf(BigDecimal amount, BigDecimal rate) {
        if (amount == null || rate == null) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 指定应收单的「在途分配」合计：其他发票已提交审批（ISSUING）占用但尚未生效
     * （completeTask 未跑）的额度。已通过单的额度已滚进 uninvoiced_amount，不重复扣。
     */
    private BigDecimal sumInFlightAllocations(Long receivableId, Long excludeInvoiceId) {
        List<InvoiceAllocation> rows = invoiceAllocationMapper.selectList(new LambdaQueryWrapper<InvoiceAllocation>()
                .eq(InvoiceAllocation::getReceivableId, receivableId)
                .eq(InvoiceAllocation::getStatus, "NORMAL"));
        Set<Long> otherInvoiceIds = rows.stream()
                .map(InvoiceAllocation::getInvoiceId)
                .filter(iid -> !Objects.equals(iid, excludeInvoiceId))
                .collect(Collectors.toSet());
        if (otherInvoiceIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<Invoice> inFlight = invoiceMapper.selectList(new LambdaQueryWrapper<Invoice>()
                .in(Invoice::getId, otherInvoiceIds)
                .eq(Invoice::getApprovalStatus, "APPROVING"));
        Set<Long> inFlightIds = inFlight.stream().map(Invoice::getId).collect(Collectors.toSet());
        return rows.stream()
                .filter(a -> inFlightIds.contains(a.getInvoiceId()))
                .map(InvoiceAllocation::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 审批通过：核销生效。单事务内滚各应收单 invoiced/uninvoiced
     * （fin_invoice.receivable_id 为主应收单，多张拆分时以明细为准）。
     */
    private void applyInvoiceAllocations(Invoice r) {
        List<InvoiceAllocation> rows = invoiceAllocationMapper.selectList(new LambdaQueryWrapper<InvoiceAllocation>()
                .eq(InvoiceAllocation::getInvoiceId, r.getId())
                .eq(InvoiceAllocation::getStatus, "NORMAL"));
        if (rows.isEmpty()) {
            return;
        }
        // RED（红冲单）反向：扣回应收单已开票、加回未开票（2026-10-25 红冲入口）
        boolean red = "RED".equals(r.getDocDirection());
        // V77：票款勾稽开关（false=不允许回款前开票时强制 开票后已开票≤已回款）
        Project proj = projectMapper.selectById(r.getProjectId());
        boolean allowInvoiceBeforeReceipt = proj != null
                && !Boolean.FALSE.equals(proj.getAllowInvoiceBeforeReceipt());
        for (InvoiceAllocation a : rows) {
            Receivable rv = receivableMapper.selectById(a.getReceivableId());
            if (rv == null) {
                continue;
            }
            BigDecimal invoiced = rv.getInvoicedAmount() == null ? BigDecimal.ZERO : rv.getInvoicedAmount();
            BigDecimal uninvoiced = rv.getUninvoicedAmount() == null ? BigDecimal.ZERO : rv.getUninvoicedAmount();
            // 蓝字减后不得为负：负数 = 有并发超开穿透了前面所有关口，账绝不能错，审批回滚（第三道闸兜底）
            BigDecimal newUninvoiced = red ? uninvoiced.add(a.getAmount()) : uninvoiced.subtract(a.getAmount());
            if (!red && newUninvoiced.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalStateException("应收单未开票余额不足以核销本次开票：" + rv.getReceivableNo()
                        + "，未开票 " + uninvoiced.toPlainString() + "，本次 " + a.getAmount().toPlainString()
                        + "，疑似并发超开穿透，请核查（本次审批已回滚）");
            }
            // 票款勾稽（V77 第三道闸兜底）：不允许回款前开票时，开票后已开票额不得超已回款额
            if (!red && !allowInvoiceBeforeReceipt) {
                BigDecimal invoicedAfter = invoiced.add(a.getAmount());
                BigDecimal received = rv.getReceivedAmount() == null ? BigDecimal.ZERO : rv.getReceivedAmount();
                if (invoicedAfter.compareTo(received) > 0) {
                    throw new IllegalStateException("项目不允许回款前开票，应收单 " + rv.getReceivableNo()
                            + " 开票后已开票 " + invoicedAfter.toPlainString() + " 将超过已回款 " + received.toPlainString()
                            + "，疑似并发穿透，审批已回滚");
                }
            }
            rv.setInvoicedAmount(red ? invoiced.subtract(a.getAmount()) : invoiced.add(a.getAmount()));
            rv.setUninvoicedAmount(newUninvoiced);
            receivableMapper.updateById(rv);
        }
        log.info("[invoice] 核销生效 invoiceId={} rows={}", r.getId(), rows.size());
    }

    /** 分配明细 → VO（detail 回填，含应收单号） */
    private List<AllocationVO> allocationVOs(Long invoiceId) {
        List<InvoiceAllocation> rows = invoiceAllocationMapper.selectList(new LambdaQueryWrapper<InvoiceAllocation>()
                .eq(InvoiceAllocation::getInvoiceId, invoiceId)
                .eq(InvoiceAllocation::getStatus, "NORMAL"));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, String> nos = receivableMapper.selectList(new LambdaQueryWrapper<Receivable>()
                        .in(Receivable::getId,
                                rows.stream().map(InvoiceAllocation::getReceivableId).collect(Collectors.toSet())))
                .stream().collect(Collectors.toMap(Receivable::getId, Receivable::getReceivableNo));
        List<AllocationVO> vos = new ArrayList<>(rows.size());
        for (InvoiceAllocation a : rows) {
            AllocationVO vo = new AllocationVO();
            vo.setReceivableId(a.getReceivableId());
            vo.setReceivableNo(nos.get(a.getReceivableId()));
            vo.setAmount(a.getAmount());
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 核销单号：IA-yyyyMMdd-NNN。
     *
     * <p>与收款侧 WO- 前缀分开：两张明细表各自 count+1，共用前缀会撞号混淆
     * （uk 都是各表内 unique，不跨表）。</p>
     */
    /**
     * 蓝字开票额度闸（双保险核心，对齐收款单 assertWithinContract 范式）：
     * 本次开票额度 ≤ 应收单未开票余额 − 其他发票在途占用。信任是系统命脉，账不能算错。
     */
    private void assertWithinUninvoiced(Long receivableId, BigDecimal amount, Long excludeInvoiceId) {
        Receivable rv = receivableMapper.selectById(receivableId);
        if (rv == null) {
            throw new IllegalArgumentException("应收单不存在：" + receivableId);
        }
        BigDecimal uninvoiced = rv.getUninvoicedAmount() == null ? BigDecimal.ZERO : rv.getUninvoicedAmount();
        BigDecimal available = uninvoiced.subtract(sumInFlightAllocations(receivableId, excludeInvoiceId));
        if (amount.compareTo(available) > 0) {
            throw new IllegalArgumentException("开票金额超出应收单未开票余额：" + rv.getReceivableNo()
                    + "，可用 " + available.toPlainString() + "，本次 " + amount.toPlainString()
                    + "。请核实金额，或按未开票余额拆分分配。");
        }
    }

    /**
     * 发票业务单号：FP-yyyyMMdd-NNN。取含逻辑删除行的当日最大序号 +1（同 generateReceiptNo 范式）——
     * 已删发票仍占用 uk_tenant_invoice_no 唯一键，复用号会撞键 500（2026-10-27 收款单同款教训）。
     */
    private String generateInvoiceNo() {
        String date = LocalDateTime.now().format(ALLOCATION_NO_DATE_FMT);
        Integer maxSeq = invoiceMapper.selectMaxDailySeq(date);
        int next = (maxSeq == null ? 0 : maxSeq) + 1;
        return String.format("FP-%s-%03d", date, next);
    }

    /**
     * 核销明细单号 IA-yyyyMMdd-NNN：取【含逻辑删除行】的当日最大序号 +1（同 generateInvoiceNo 范式）。
     *
     * <p>⚠️ 不能用 BaseMapper.selectCount + 1：MP 逻辑删除拦截器会给它追加 is_deleted=0，
     * 而 uk_tenant_allocation_no(tenant_id, allocation_no) 唯一键不区分 is_deleted ——
     * 软删行仍物理占键。发票编辑（update）正是「软删旧分配行 → 重新生成单号」，
     * 用 count 会把刚软删的号当成可用号复用 → DuplicateKey 撞键（2026-10-31 实锤：
     * Duplicate entry '0-IA-20261031-001'，编辑已驳回发票必然失败）。走原生 SQL 取 MAX+1 即根治。</p>
     */
    private String generateAllocationNo() {
        String date = LocalDateTime.now().format(ALLOCATION_NO_DATE_FMT);
        Integer maxSeq = invoiceAllocationMapper.selectMaxDailySeq(date);
        int next = (maxSeq == null ? 0 : maxSeq) + 1;
        return String.format("IA-%s-%03d", date, next);
    }

    private InvoiceVO toVO(Invoice r, Map<String, DictData> statusDict, Map<String, DictData> typeDict,
                           Map<String, DictData> docDict, Map<Long, String> userNames,
                           Map<Long, Project> projects, Map<Long, Receivable> receivables) {
        InvoiceVO vo = new InvoiceVO();
        vo.setId(r.getId());
        vo.setInvoiceNo(r.getInvoiceNo());
        vo.setInvoiceCode(r.getInvoiceCode());
        vo.setProjectId(r.getProjectId());
        vo.setReceivableId(r.getReceivableId());
        vo.setInvoiceAmount(r.getInvoiceAmount());
        vo.setTaxRate(r.getTaxRate());
        vo.setTaxAmount(r.getTaxAmount());
        vo.setInvoiceType(r.getInvoiceType());
        DictData type = typeDict.get(r.getInvoiceType());
        vo.setInvoiceTypeName(type != null ? type.getDictLabel() : null);
        vo.setInvoiceTypeStyle(type != null ? type.getStyle() : null);
        vo.setDocDirection(r.getDocDirection());
        DictData doc = docDict.get(r.getDocDirection());
        vo.setDocDirectionName(doc != null ? doc.getDictLabel() : null);
        vo.setInvoiceDate(r.getInvoiceDate());
        vo.setInvoiceFilePath(r.getInvoiceFilePath());
        vo.setApprovalStatus(r.getApprovalStatus());
        DictData st = statusDict.get(r.getApprovalStatus());
        vo.setApprovalStatusName(st != null ? st.getDictLabel() : null);
        vo.setApprovalStatusStyle(st != null ? st.getStyle() : null);
        vo.setProcessInstanceId(r.getProcessInstanceId());
        vo.setApplicantId(r.getApplicantId());
        vo.setApplicantName(r.getApplicantId() != null ? userNames.get(r.getApplicantId()) : null);
        vo.setClerkId(r.getClerkId());
        vo.setClerkName(r.getClerkId() != null ? userNames.get(r.getClerkId()) : null);
        vo.setCreatedAt(r.getCreatedAt());

        // 项目 / 应收单信息由调用方批量预取传入（避免逐条 selectById 的 N+1）
        Project p = r.getProjectId() == null ? null : projects.get(r.getProjectId());
        if (p != null) {
            vo.setProjectName(p.getProjectName());
        }
        Receivable rec = r.getReceivableId() == null ? null : receivables.get(r.getReceivableId());
        if (rec != null) {
            vo.setReceivableNo(rec.getReceivableNo());
        }
        if (r.getProcessInstanceId() != null && "APPROVING".equals(r.getApprovalStatus())) {
            List<TaskInfo> tasks = engine.listTasks(r.getProcessInstanceId());
            if (tasks != null && !tasks.isEmpty()) {
                vo.setMyPendingTaskId(tasks.get(0).getTaskId());
                // V75：只有多节点流程才回填「当前节点名」——单节点流程（发货/收款/发票）
                // 没有「第几级」概念，回填的 taskName 恒等于流程名，纯噪音
                if (FlowNodeUtil.isMultiNode(processDefMapper, INVOICE_PROCESS_CODE)) {
                    vo.setCurrentNodeName(tasks.get(0).getTaskName());
                }
            }
        }
        return vo;
    }

    private Map<String, DictData> dictMap(String typeCode) {
        List<DictData> rows = dictDataMapper.selectList(new LambdaQueryWrapper<DictData>()
                .eq(DictData::getTypeCode, typeCode).eq(DictData::getStatus, "ENABLED"));
        Map<String, DictData> map = new HashMap<>();
        for (DictData d : rows) {
            map.put(d.getDictValue(), d);
        }
        return map;
    }

    private Map<Long, String> userNamesOf(List<Invoice> invoices) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Invoice r : invoices) {
            if (r.getApplicantId() != null) ids.add(r.getApplicantId());
            if (r.getClerkId() != null) ids.add(r.getClerkId());
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .in(User::getId, ids).select(User::getId, User::getNickname, User::getUsername));
        Map<Long, String> map = new HashMap<>();
        for (User u : users) {
            String name = (u.getNickname() != null && !u.getNickname().isBlank())
                    ? u.getNickname() : u.getUsername();
            map.put(u.getId(), name);
        }
        return map;
    }

    /** 提交时通知开票员（ROLE_INVOICER，模板 INVOICE_DONE） */
    private void notifySubmitted(Invoice r, String code) {
        List<Long> receivers = resolveRoleUserIds(ROLE_INVOICER);
        if (receivers.isEmpty()) {
            log.warn("[invoice] 提交审批通知无接收人：role={} 下无用户，请检查 sys_user_role 角色绑定 invoiceId={}",
                    ROLE_INVOICER, r.getId());
            return;
        }
        Project p = projectMapper.selectById(r.getProjectId());
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode(code);
        cmd.setReceivers(receivers);
        cmd.setBizType("INVOICE");
        cmd.setBizId(String.valueOf(r.getId()));
        cmd.setLinkUrl("/approval");
        cmd.setDedupKey(code + ":INVOICE:" + r.getId());
        cmd.setVars(Map.of(
                "bizTitle", p != null && p.getProjectName() != null ? p.getProjectName() : "",
                "invoiceNo", r.getInvoiceNo() == null ? "" : r.getInvoiceNo()));
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    /** 审批通过/驳回通知申请人（模板 code） */
    private void notifyResult(Invoice r, String code, String result, String comment) {
        Long applicantId = r.getApplicantId();
        if (applicantId == null) {
            return;
        }
        Project p = projectMapper.selectById(r.getProjectId());
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode(code);
        cmd.setReceivers(List.of(applicantId));
        cmd.setBizType("INVOICE");
        cmd.setBizId(String.valueOf(r.getId()));
        cmd.setLinkUrl("/invoice");
        cmd.setDedupKey(code + ":INVOICE:" + r.getId());
        cmd.setVars(Map.of(
                "bizTitle", p != null && p.getProjectName() != null ? p.getProjectName() : "",
                "invoiceNo", r.getInvoiceNo() == null ? "" : r.getInvoiceNo()));
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    /** 撤回通知（模板 INVOICE_DONE；无人审批过则不发） */
    private void notifyWithdrawn(Invoice r, List<Long> approvedUserIds) {
        if (approvedUserIds == null || approvedUserIds.isEmpty()) {
            log.debug("[notify] 撤回时尚无人审批，跳过通知 invoiceId={}", r.getId());
            return;
        }
        Project p = projectMapper.selectById(r.getProjectId());
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("INVOICE_DONE");
        cmd.setReceivers(approvedUserIds);
        cmd.setBizType("INVOICE");
        cmd.setBizId(String.valueOf(r.getId()));
        cmd.setLinkUrl("/invoice");
        cmd.setDedupKey("INVOICE_DONE:INVOICE:" + r.getId());
        cmd.setVars(Map.of(
                "bizTitle", p != null && p.getProjectName() != null ? p.getProjectName() : "",
                "invoiceNo", r.getInvoiceNo() == null ? "" : r.getInvoiceNo()));
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    /** 角色 code → 全员 userId */
    private List<Long> resolveRoleUserIds(String roleCode) {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleCode, roleCode).select(Role::getId));
        if (role == null) {
            return Collections.emptyList();
        }
        List<UserRole> urs = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRoleId, role.getId()));
        return urs.stream().map(UserRole::getUserId).collect(Collectors.toList());
    }

    private Long currentUserId() {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, currentUsername()).select(User::getId));
        return u == null ? null : u.getId();
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // ===================== 附件软关联（2026-10-25 V62 真上传） =====================

    /**
     * 单据落库后挂载附件：把前端上传时（单据尚无 id）没带 bizId 的文件改写 bizType/bizId。
     *
     * <p>与发货签收证据同构（V54）——附件不落在业务表列上，只存在于 sys_file_storage。
     * bizType 固定 {@link #INVOICE_ATTACH_BIZ_TYPE}，与 file_biz_type 字典 value 一致。</p>
     */
    private void bindAttachments(Long invoiceId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty() || invoiceId == null) {
            return;
        }
        for (Long fid : fileIds) {
            FileStorage f = fileStorageMapper.selectById(fid);
            if (f == null) {
                continue;
            }
            f.setBizType(INVOICE_ATTACH_BIZ_TYPE);
            f.setBizId(invoiceId);
            fileStorageMapper.updateById(f);
        }
    }

    /**
     * 编辑态重新挂载：以本次提交的全量 fileIds 为准。
     *
     * <p>先挂载（新上传的），再把「已属于本单但不在本次列表里」的文件解绑（bizId 置 NULL）——
     * 前端删除附件时不调删除接口（文件本身保留在库），只是在表单里移除，故这里做差分解绑。</p>
     */
    private void rebindAttachments(Long invoiceId, List<Long> fileIds) {
        bindAttachments(invoiceId, fileIds);
        List<Long> keep = fileIds == null ? List.of() : fileIds;
        List<FileStorage> bound = fileStorageMapper.selectList(new LambdaQueryWrapper<FileStorage>()
                .eq(FileStorage::getBizType, INVOICE_ATTACH_BIZ_TYPE)
                .eq(FileStorage::getBizId, invoiceId));
        for (FileStorage f : bound) {
            if (!keep.contains(f.getId())) {
                f.setBizId(null);
                fileStorageMapper.updateById(f);
            }
        }
    }

    /** 按 bizType+bizId 回查附件 ID 列表（顺序稳定：按文件主键升序）。 */
    private List<Long> loadAttachmentIds(Long invoiceId) {
        List<FileStorage> files = fileStorageMapper.selectList(new LambdaQueryWrapper<FileStorage>()
                .eq(FileStorage::getBizType, INVOICE_ATTACH_BIZ_TYPE)
                .eq(FileStorage::getBizId, invoiceId)
                .orderByAsc(FileStorage::getId));
        return files.stream().map(FileStorage::getId).collect(Collectors.toList());
    }
}
