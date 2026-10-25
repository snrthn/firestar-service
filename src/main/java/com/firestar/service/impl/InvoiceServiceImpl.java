package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.annotation.Auditable;
import com.firestar.common.BatchLoader;
import com.firestar.common.DataScopeHelper;
import com.firestar.dto.InvoiceRequest;
import com.firestar.dto.InvoiceVO;
import com.firestar.dto.NotifyCommand;
import com.firestar.dto.TaskInfo;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.entity.DictData;
import com.firestar.entity.FileStorage;
import com.firestar.entity.Invoice;
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
import java.time.format.DateTimeFormatter;
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

    private static final String DICT_INVOICE_STATUS = "invoice_status";
    private static final String DICT_INVOICE_TYPE = "invoice_type";
    private static final String DICT_DOC_DIRECTION = "doc_direction";

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

    @Autowired
    public InvoiceServiceImpl(InvoiceMapper invoiceMapper, FileStorageMapper fileStorageMapper,
                             ReceivableMapper receivableMapper,
                             ProjectMapper projectMapper, DictDataMapper dictDataMapper,
                             ProcessDefMapper processDefMapper, WorkflowEngineService engine,
                             NotificationService notificationService, UserMapper userMapper,
                             RoleMapper roleMapper, UserRoleMapper userRoleMapper) {
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
        if (req.getReceivableId() == null) {
            throw new IllegalArgumentException("应收单不能为空");
        }
        Receivable receivable = receivableMapper.selectById(req.getReceivableId());
        if (receivable == null) {
            throw new IllegalArgumentException("应收单不存在：" + req.getReceivableId());
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
        if (req.getInvoiceAmount() == null || req.getInvoiceAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("开票金额必须为正");
        }

        Invoice invoice = new Invoice();
        invoice.setProjectId(project.getId());
        invoice.setReceivableId(receivable.getId());
        invoice.setInvoiceAmount(req.getInvoiceAmount());
        invoice.setTaxRate(req.getTaxRate());
        invoice.setTaxAmount(req.getTaxAmount());
        invoice.setInvoiceNo(req.getInvoiceNo());
        invoice.setInvoiceCode(req.getInvoiceCode());
        invoice.setInvoiceType(req.getInvoiceType());
        invoice.setInvoiceDate(req.getInvoiceDate());
        // invoice_file_path 已废弃（V62 真上传），附件走软关联，落库前不再写该列
        // 单据方向默认蓝字（红字发票为后续扩展，本期仅蓝字）
        invoice.setDocDirection("BLUE");
        // 创建即草稿，待提交再拉审批
        invoice.setInvoiceStatus("PENDING");
        // 归属人 = 当前用户（申请人）
        invoice.setApplicantId(operatorId);
        invoice.setClerkId(null);
        invoiceMapper.insert(invoice);
        // 附件软关联挂载：前端新建时单据尚无 id，上传未带 bizId，此处拿到自增 id 后统一挂载
        bindAttachments(invoice.getId(), req.getAttachmentFileIds());

        log.info("[invoice] 创建发票草稿 id={} projectId={} receivableId={} amount={}",
                invoice.getId(), project.getId(), receivable.getId(), req.getInvoiceAmount());
        return invoice.getId();
    }

    // ===================== 提交审批 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Auditable(operationType = "UPDATE", targetType = "INVOICE", targetIdSpEl = "#id",
            entityClass = Invoice.class, fields = {"invoiceStatus", "processInstanceId"})
    public void submit(Long id) {
        Invoice r = invoiceMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("发票不存在：" + id);
        }
        if (!"PENDING".equals(r.getInvoiceStatus())) {
            throw new IllegalArgumentException("仅草稿态可提交审批，当前：" + r.getInvoiceStatus());
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
        r.setInvoiceStatus("ISSUING");
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
        if (!"PENDING".equals(r.getInvoiceStatus())) {
            throw new IllegalArgumentException("仅草稿态可编辑，当前：" + r.getInvoiceStatus());
        }
        if (req.getInvoiceAmount() == null || req.getInvoiceAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("开票金额必须为正");
        }
        r.setInvoiceAmount(req.getInvoiceAmount());
        r.setTaxRate(req.getTaxRate());
        r.setTaxAmount(req.getTaxAmount());
        r.setInvoiceNo(req.getInvoiceNo());
        r.setInvoiceCode(req.getInvoiceCode());
        r.setInvoiceType(req.getInvoiceType());
        r.setInvoiceDate(req.getInvoiceDate());
        // invoice_file_path 已废弃（V62 真上传），不再写该列
        r.setUpdatedBy(operatorId);
        invoiceMapper.updateById(r);
        // 附件以本次提交的全量列表为准：新上传的挂上，前端删掉的会被解绑（bizId 置 NULL）
        rebindAttachments(id, req.getAttachmentFileIds());
        log.info("[invoice] 编辑发票 id={} amount={} user={}", id, req.getInvoiceAmount(), currentUsername());
    }

    // ===================== 撤回（仅本人+admin） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Auditable(operationType = "UPDATE", targetType = "INVOICE", targetIdSpEl = "#id",
            entityClass = Invoice.class, fields = {"invoiceStatus"})
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
        r.setInvoiceStatus("CANCELLED");
        r.setUpdatedBy(currentUserId());
        invoiceMapper.updateById(r);
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
                r.setInvoiceStatus("ISSUED");
                // 开票员 = 当前操作人（审批通过人）
                r.setClerkId(currentUserId());
                r.setUpdatedBy(currentUserId());
                invoiceMapper.updateById(r);
                // 累加 fin_project.invoiced_amount（金额纪律：null 当 0，add）
                accumulateProjectInvoiced(r.getProjectId(), r.getInvoiceAmount());
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
        r.setInvoiceStatus("REJECTED");
        r.setUpdatedBy(currentUserId());
        invoiceMapper.updateById(r);
        log.info("[invoice] 审批驳回回写 id={} comment={}", r.getId(), comment);
        notifyResult(r, "INVOICE_DONE", "已退回", comment);
    }

    // ===================== 查询 =====================

    @Override
    public IPage<InvoiceVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                                 Long projectId, Long receivableId, String status) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 500L);
        String statusEq = status == null || status.isBlank() ? null : status.trim();

        Long me = currentUserId();
        if (DataScopeHelper.denyAll(me)) {
            return new Page<>(current, size, 0);
        }
        IPage<Invoice> page = invoiceMapper.selectPage(new Page<>(current, size),
                DataScopeHelper.own(
                        new LambdaQueryWrapper<Invoice>()
                                .eq(projectId != null, Invoice::getProjectId, projectId)
                                .eq(receivableId != null, Invoice::getReceivableId, receivableId)
                                .eq(statusEq != null, Invoice::getInvoiceStatus, statusEq)
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
        return vo;
    }

    @Override
    public List<TimelineNodeVO> timeline(Long id) {
        // processKey 必传：businessKey 跨表相撞（发票 #2 = 收款单 #2），不带类型过滤会串台
        return engine.listTimelineByBusinessKey(INVOICE_PROCESS_CODE, String.valueOf(id));
    }

    // ===================== 私有 =====================

    /** 累加项目已开票（fin_project.invoiced_amount）；金额纪律：null 当 0，add */
    private void accumulateProjectInvoiced(Long projectId, BigDecimal amount) {
        if (projectId == null || amount == null) {
            return;
        }
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            return;
        }
        BigDecimal base = p.getInvoicedAmount() == null ? BigDecimal.ZERO : p.getInvoicedAmount();
        p.setInvoicedAmount(base.add(amount));
        projectMapper.updateById(p);
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
        vo.setInvoiceStatus(r.getInvoiceStatus());
        DictData st = statusDict.get(r.getInvoiceStatus());
        vo.setInvoiceStatusName(st != null ? st.getDictLabel() : null);
        vo.setInvoiceStatusStyle(st != null ? st.getStyle() : null);
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
        if (r.getProcessInstanceId() != null && "ISSUING".equals(r.getInvoiceStatus())) {
            List<TaskInfo> tasks = engine.listTasks(r.getProcessInstanceId());
            if (tasks != null && !tasks.isEmpty()) {
                vo.setMyPendingTaskId(tasks.get(0).getTaskId());
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
