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
import com.firestar.dto.NotifyCommand;
import com.firestar.dto.ReceiptRequest;
import com.firestar.dto.ReceiptVO;
import com.firestar.dto.TaskInfo;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.entity.DictData;
import com.firestar.entity.FileStorage;
import com.firestar.entity.Project;
import com.firestar.entity.Receipt;
import com.firestar.entity.Receivable;
import com.firestar.entity.Role;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.entity.ProcessDef;
import com.firestar.entity.WriteOff;
import com.firestar.mapper.CustomerMapper;
import com.firestar.mapper.DictDataMapper;
import com.firestar.mapper.FileStorageMapper;
import com.firestar.mapper.ProcessDefMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.ReceiptMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.mapper.WriteOffMapper;
import com.firestar.service.NotificationService;
import com.firestar.service.ReceiptService;
import com.firestar.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
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
 * 收款（回款）业务逻辑实现（与立项/发货一致走 Flowable 审批流）。
 *
 * <p>流程码 {@code receipt_approval}，审批节点角色 {@code ROLE_CASHIER}（流程定义 + 角色授权
 * 已在迁移 V60 种好，由 ProcessDefAutoDeployRunner 自动部署，业务层只管 engine.start 拉起）。</p>
 *
 * <p>金额纪律（需求）：一律 BigDecimal，null 当 0，用 add 不用 +=；receiptAmount 是唯一直属
 * 输入，创建即落库，审批通过只置终态，不再改金额。</p>
 */
@Slf4j
@Service
public class ReceiptServiceImpl implements ReceiptService {

    /** 收款审批流程 process_code */
    public static final String RECEIPT_PROCESS_CODE = "receipt_approval";

    /** 审批节点候选角色（出纳） */
    private static final String ROLE_CASHIER = "ROLE_CASHIER";

    /**
     * 附件软关联业务类型（2026-10-25 V62 真上传）。
     *
     * <p>字面量必须与 file_biz_type 字典的 dict_value 严格一致（V20/V54 口径），
     * 否则该文件在文件管理页不可见、不可筛。</p>
     */
    public static final String RECEIPT_ATTACH_BIZ_TYPE = "receipt";

    private static final DateTimeFormatter RECEIPT_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String DICT_APPROVAL_STATUS = "approval_status";
    private static final String DICT_RECEIPT_BUSINESS_STATUS = "receipt_business_status";
    private static final String DICT_RECEIPT_TYPE = "receipt_type";
    /** 红字/退款类款项类型（V78 从 receipt_type 拆出：REFUND 退货退款 / RED 红冲） */
    private static final String DICT_RECEIPT_RED_TYPE = "receipt_red_type";
    private static final String DICT_DOC_DIRECTION = "doc_direction";

    private final ReceiptMapper receiptMapper;
    private final FileStorageMapper fileStorageMapper;
    private final ProjectMapper projectMapper;
    private final DictDataMapper dictDataMapper;
    private final ProcessDefMapper processDefMapper;
    private final WorkflowEngineService engine;
    private final NotificationService notificationService;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final WriteOffMapper writeOffMapper;
    private final ReceivableMapper receivableMapper;
    /** 客户主表（V68）：V72 起 fin_project 不再存客户名，展示名需按 customer_id 联表翻译 */
    private final CustomerMapper customerMapper;

    @Autowired
    public ReceiptServiceImpl(ReceiptMapper receiptMapper, FileStorageMapper fileStorageMapper,
                              ProjectMapper projectMapper,                               DictDataMapper dictDataMapper, ProcessDefMapper processDefMapper,
                              WorkflowEngineService engine, NotificationService notificationService,
                              UserMapper userMapper, RoleMapper roleMapper, UserRoleMapper userRoleMapper,
                              WriteOffMapper writeOffMapper, ReceivableMapper receivableMapper,
                              CustomerMapper customerMapper) {
        this.receiptMapper = receiptMapper;
        this.fileStorageMapper = fileStorageMapper;
        this.projectMapper = projectMapper;
        this.dictDataMapper = dictDataMapper;
        this.processDefMapper = processDefMapper;
        this.engine = engine;
        this.notificationService = notificationService;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.writeOffMapper = writeOffMapper;
        this.receivableMapper = receivableMapper;
        this.customerMapper = customerMapper;
    }

    // ===================== 创建（草稿，不拉审批） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Auditable(operationType = "CREATE", targetType = "RECEIPT", targetIdSpEl = "#result",
            entityClass = Receipt.class,
            fields = {"projectId", "receiptAmount", "receiptType", "receiptDate", "docDirection"})
    public Long create(ReceiptRequest req) {
        Long operatorId = currentUserId();
        if (req.getProjectId() == null) {
            throw new IllegalArgumentException("项目不能为空");
        }
        // 方案A（2026-10-27）：总额口径——有分配 → 总额=Σ分配（只读自动算）；无分配=预收 → 用单据填写总额
        boolean hasAlloc = req.getAllocations() != null && !req.getAllocations().isEmpty();
        BigDecimal total = hasAlloc
                ? req.getAllocations().stream()
                .map(a -> a.getAmount() == null ? BigDecimal.ZERO : a.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                : req.getReceiptAmount();
        if (total == null || total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(hasAlloc ? "分配金额合计必须大于 0" : "收款金额必须大于 0");
        }
        if (req.getReceiptType() == null || req.getReceiptType().isBlank()) {
            throw new IllegalArgumentException("款项类型不能为空");
        }
        if (req.getReceiptDate() == null) {
            throw new IllegalArgumentException("收款日期不能为空");
        }
        if (req.getAttachmentFileIds() == null || req.getAttachmentFileIds().isEmpty()) {
            throw new IllegalArgumentException("银行回单附件不能为空");
        }
        Project project = projectMapper.selectById(req.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("项目不存在：" + req.getProjectId());
        }

        Receipt receipt = new Receipt();
        receipt.setProjectId(project.getId());
        receipt.setReceiptAmount(total);
        receipt.setReceiptType(req.getReceiptType());
        receipt.setReceiptDate(req.getReceiptDate());
        // 注意：bank_receipt_path 已废弃（V62 真上传），附件走软关联，落库前不再写该列
        // docDirection：默认 BLUE（蓝字）；RED（红字）= 红冲单，须挂已审批通过的原单
        // （2026-10-25 红冲入口：正数金额+方向标记，汇总/核销时按方向代数运算）
        String direction = "RED".equals(req.getDocDirection()) ? "RED" : "BLUE";
        receipt.setDocDirection(direction);
        // 超收硬校验（双保险·后端关口一）：蓝字收款不得使累计已收超过合同金额；信任是命脉，账不能算错
        assertWithinContract(project, total, direction, null);
        if ("RED".equals(direction)) {
            // ③ 详情红冲（冲蓝字原单）：带 originalReceiptId，走原单校验；
            // ② 手动红冲 / ① 自动退款（消耗红字应收贷项）：originalReceiptId 为空，跳过原单校验，
            // 核销行指向红字应收，由 applyWriteOffs 按目标方向走消耗语义。
            // （2026-10-28 补堵：此前一律要求原单 → ② 手动红冲永远建不出单）
            if (req.getOriginalReceiptId() != null) {
                receipt.setOriginalReceiptId(validateRedFlush(req, project).getId());
            }
        }
        // 创建即草稿，待提交再拉审批
        receipt.setApprovalStatus("PENDING_APPROVAL");
        receipt.setReceiptNo(generateReceiptNo());
        // 归属人 = createdBy（LogicDeleteEntity 父类列）
        receipt.setCreatedBy(operatorId);
        receiptMapper.insert(receipt);
        // 附件软关联挂载：前端新建时单据尚无 id，上传未带 bizId，此处拿到自增 id 后统一挂载
        bindAttachments(receipt.getId(), req.getAttachmentFileIds());
        // 核销分配明细落库（V65 双向拆分）：计划态不滚数字，completeTask 审批通过才生效
        saveAllocations(receipt.getId(), req.getAllocations(), total, project.getId());

        log.info("[receipt] 创建收款单草稿 id={} no={} projectId={} amount={}",
                receipt.getId(), receipt.getReceiptNo(), project.getId(), req.getReceiptAmount());
        return receipt.getId();
    }

    // ===================== 提交审批 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Auditable(operationType = "UPDATE", targetType = "RECEIPT", targetIdSpEl = "#id",
            entityClass = Receipt.class, fields = {"approvalStatus", "processInstanceId"})
    public void submit(Long id) {
        Receipt r = receiptMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("收款单不存在：" + id);
        }
        // 草稿态 + 已驳回态可提交（2026-10-25 抄用友作业：被驳回单据回到制单人，
        // 状态等同「编写中」，可修改后再次提交；驳回非终态，撤回才是终态）
        if (!"PENDING_APPROVAL".equals(r.getApprovalStatus()) && !"REJECTED".equals(r.getApprovalStatus())) {
            throw new IllegalArgumentException("仅草稿态或已驳回态可提交审批，当前：" + r.getApprovalStatus());
        }
        // 流程定义必须存在且启用
        ProcessDef def = processDefMapperSafe();
        if (def == null) {
            throw new IllegalArgumentException("收款审批流程未配置或已停用：" + RECEIPT_PROCESS_CODE);
        }
        // 拉审批：一步 ROLE_CASHIER 节点
        Map<String, Object> vars = new HashMap<>();
        vars.put("applicantUsername", currentUsername());
        vars.put("applicantId", currentUserId());
        String instanceId = engine.start(RECEIPT_PROCESS_CODE, String.valueOf(id), vars);
        r.setProcessInstanceId(instanceId);
        r.setApprovalStatus("APPROVING");
        receiptMapper.updateById(r);

        log.info("[receipt] 提交收款审批 id={} instanceId={} applicant={}", id, instanceId, currentUsername());
        // 提交通知：通知出纳（ROLE_CASHIER）
        notifySubmitted(r, "RECEIPT_AUDIT");
    }

    // ===================== 编辑（仅草稿态 / 本人+admin） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Auditable(operationType = "UPDATE", targetType = "RECEIPT", targetIdSpEl = "#id",
            entityClass = Receipt.class, fields = {"projectId", "receiptAmount", "receiptType", "receiptDate"})
    public void update(Long id, ReceiptRequest req, String username, boolean isAdmin) {
        Long operatorId = currentUserId();
        Receipt r = receiptMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("收款单不存在：" + id);
        }
        // 归属校验：写类仅申请人本人（createdBy）或 ROLE_ADMIN
        if (!isAdmin && !Objects.equals(r.getCreatedBy(), operatorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收款单不存在或无权操作：" + id);
        }
        // 草稿态（PENDING_APPROVAL）+ 已驳回态可编辑（用友范式：驳回=回到编写中，可改后重提；APPROVING 在审批链中不可改）
        if (!"PENDING_APPROVAL".equals(r.getApprovalStatus()) && !"REJECTED".equals(r.getApprovalStatus())) {
            throw new IllegalArgumentException("仅草稿态或已驳回态可编辑，当前：" + r.getApprovalStatus());
        }
        if (req.getProjectId() == null) {
            throw new IllegalArgumentException("项目不能为空");
        }
        Project project = projectMapper.selectById(req.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("项目不存在：" + req.getProjectId());
        }
        // 方案A（2026-10-27）：总额口径——有分配 → 总额=Σ分配（只读自动算）；无分配=预收 → 用单据填写总额
        boolean hasAllocU = req.getAllocations() != null && !req.getAllocations().isEmpty();
        BigDecimal total = hasAllocU
                ? req.getAllocations().stream()
                .map(a -> a.getAmount() == null ? BigDecimal.ZERO : a.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                : req.getReceiptAmount();
        if (total == null || total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(hasAllocU ? "分配金额合计必须大于 0" : "收款金额必须大于 0");
        }
        if (req.getReceiptType() == null || req.getReceiptType().isBlank()) {
            throw new IllegalArgumentException("款项类型不能为空");
        }
        if (req.getReceiptDate() == null) {
            throw new IllegalArgumentException("收款日期不能为空");
        }
        if (req.getAttachmentFileIds() == null || req.getAttachmentFileIds().isEmpty()) {
            throw new IllegalArgumentException("银行回单附件不能为空");
        }
        // 超收硬校验（双保险·后端关口一，编辑草稿同口径）
        // 方向以数据库原单为准：update 不允许改 docDirection，且编辑请求不携带该字段
        // （前端仅红冲创建时传 RED），用 req 判会把编辑中的红冲单误判成蓝字而误拦（2026-10-27 实锤）
        assertWithinContract(project, total,
                "RED".equals(r.getDocDirection()) ? "RED" : "BLUE", r.getId());
        // 不允许更换项目（换项目等于换合同，应新建；且汇总列是项目维度的）
        if (!Objects.equals(req.getProjectId(), r.getProjectId())) {
            throw new IllegalArgumentException("不允许更换项目，请新建收款单");
        }
        r.setReceiptAmount(total);
        r.setReceiptType(req.getReceiptType());
        r.setReceiptDate(req.getReceiptDate());
        // bank_receipt_path 已废弃（V62 真上传），不再写该列
        r.setUpdatedBy(operatorId);
        receiptMapper.updateById(r);
        // 附件以本次提交的全量列表为准：新上传的挂上，前端删掉的会被解绑（bizId 置 NULL）
        rebindAttachments(id, req.getAttachmentFileIds());
        // 分配明细同口径全量替换：计划态未生效，硬删重插无痕（V65）
        writeOffMapper.delete(new LambdaQueryWrapper<WriteOff>().eq(WriteOff::getReceiptId, id));
        saveAllocations(id, req.getAllocations(), total, r.getProjectId());
        log.info("[receipt] 编辑收款单 id={} amount={} allocations={} user={}",
                id, total, req.getAllocations() == null ? 0 : req.getAllocations().size(),
                currentUsername());
    }

    // ===================== 撤回（仅本人+admin） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Auditable(operationType = "UPDATE", targetType = "RECEIPT", targetIdSpEl = "#id",
            entityClass = Receipt.class, fields = {"approvalStatus"})
    public void withdraw(Long id, String username, boolean isAdmin) {
        Receipt r = receiptMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("收款单不存在：" + id);
        }
        if (!isAdmin && !Objects.equals(r.getCreatedBy(), currentUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收款单不存在或无权操作：" + id);
        }
        if (r.getProcessInstanceId() == null || r.getProcessInstanceId().isBlank()) {
            throw new IllegalArgumentException("该收款单没有进行中的审批流程，无需撤回");
        }
        // 引擎三道闸：发起人身份（admin 不代撤）+ 流程仍在运行；返回已审批人 userId
        List<Long> approvedUserIds = engine.withdraw(r.getProcessInstanceId(), currentUsername(),
                WorkflowEngineService.WITHDRAW_REASON_PREFIX + "收款申请，收款单 id=" + id);
        r.setApprovalStatus("CANCELLED");
        r.setUpdatedBy(currentUserId());
        receiptMapper.updateById(r);
        // 分配明细硬删（V65）：withdraw 只可能发生在审批中（引擎挡已结束流程），
        // 此时 completeTask 尚未跑过、应收单/项目数字未滚 → 删计划行即守恒，无需反核销
        writeOffMapper.delete(new LambdaQueryWrapper<WriteOff>().eq(WriteOff::getReceiptId, id));
        log.info("[receipt] 申请人撤回 id={} user={} 已审批人={}", id, currentUsername(), approvedUserIds);
        notifyWithdrawn(r, approvedUserIds);
    }

    // ===================== 审批通过回调 =====================

    @Override
    @Auditable(operationType = "APPROVE", targetType = "APPROVAL", targetIdSpEl = "#taskId",
            remark = "收款审批通过")
    public void completeTask(String taskId, Map<String, Object> variables) {
        // 审批留言从 variables.comment 透传至引擎评论（对齐 ShipmentServiceImpl.completeTask）
        String comment = variables == null ? null : Objects.toString(variables.get("comment"), null);
        if (comment != null && comment.isBlank()) {
            comment = null;
        }
        // 时序双保险：complete 前先拿 businessKey（参考 ProjectServiceImpl.completeTask）
        String bizKeyUpfront = engine.businessKeyOfTask(taskId);
        String instanceId = engine.complete(taskId, variables, comment);
        List<TaskInfo> next = engine.listTasks(instanceId);
        if (next.isEmpty()) {
            // 一步审批流程走完：回写终态 + 累加项目已收款 + 通知
            String bizKey = bizKeyUpfront != null ? bizKeyUpfront : engine.getBusinessKey(instanceId);
            Receipt r = (bizKey != null) ? receiptMapper.selectById(Long.parseLong(bizKey)) : null;
            if (r != null) {
                // 超收硬校验（双保险·后端关口二）：审批通过回写前再次确认不顶过合同
                // （防 create 被绕过 / 并发双审；信任是命脉，账不能算错）
                Project proj = projectMapper.selectById(r.getProjectId());
                assertWithinContract(proj, r.getReceiptAmount(), r.getDocDirection(), r.getId());
                r.setReceiptStatus("RECEIVED");
                r.setApprovalStatus("APPROVED");
                receiptMapper.updateById(r);
                // 累加 fin_project.collected_amount（金额纪律：null 当 0，add）
                accumulateProjectCollected(r.getProjectId(), r.getReceiptAmount(), r.getDocDirection());
                // 核销生效（V65）：按分配明细滚各应收单已回款 + 本单已核销列
                applyWriteOffs(r);
                log.info("[receipt] 审批通过回写 id={} amount={}", r.getId(), r.getReceiptAmount());
                notifyResult(r, "RECEIPT_AUDIT", "已通过", null);
            } else {
                log.warn("[receipt] 审批通过但未回写：bizKey={} 找不到收款单 instanceId={}", bizKey, instanceId);
            }
        }
    }

    // ===================== 审批驳回 =====================

    @Override
    @Auditable(operationType = "REJECT", targetType = "APPROVAL", targetIdSpEl = "#taskId",
            remark = "收款审批驳回")
    public void rejectTask(String taskId, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("驳回理由不能为空");
        }
        // 候选人校验（引擎层）：terminate 路径不走 complete/reject（引擎内部），需显式过闸
        engine.assertTaskHandleable(taskId);
        // 审批中任务仍在 runtime，businessKeyOfTask 必中
        String bizKey = engine.businessKeyOfTask(taskId);
        if (bizKey == null) {
            log.warn("[receipt] 驳回时取不到业务键（任务可能已结束）taskId={}", taskId);
            return;
        }
        Receipt r = receiptMapper.selectById(Long.parseLong(bizKey));
        if (r == null) {
            log.warn("[receipt] 驳回时找不到收款单 bizKey={} taskId={}", bizKey, taskId);
            return;
        }
        // 单节点审批：无「退回申请人」节点，直接终结流程实例（保留 processInstanceId 便于排查）
        if (r.getProcessInstanceId() != null) {
            engine.terminate(r.getProcessInstanceId(), "收款申请被退回：" + comment);
        }
        r.setApprovalStatus("REJECTED");
        r.setUpdatedBy(currentUserId());
        receiptMapper.updateById(r);
        log.info("[receipt] 审批驳回回写 id={} comment={}", r.getId(), comment);
        notifyResult(r, "RECEIPT_AUDIT", "已退回", comment);
    }

    // ===================== 查询 =====================

    @Override
    public IPage<ReceiptVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                                 Long projectId, String status, String receiptNo, String projectName,
                                 String receiptType, String docDirection, LocalDate dateFrom, LocalDate dateTo) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 500L);
        String statusEq = status == null || status.isBlank() ? null : status.trim();
        String noKw = receiptNo == null || receiptNo.isBlank() ? null : receiptNo.trim();
        String nameKw = projectName == null || projectName.isBlank() ? null : projectName.trim();
        String typeEq = receiptType == null || receiptType.isBlank() ? null : receiptType.trim();
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
        // 行级隔离：非 admin 只看 createdBy = 自己的收款单
        IPage<Receipt> page = receiptMapper.selectPage(new Page<>(current, size),
                DataScopeHelper.own(
                        new LambdaQueryWrapper<Receipt>()
                                .eq(projectId != null, Receipt::getProjectId, projectId)
                                .eq(statusEq != null, Receipt::getApprovalStatus, statusEq)
                                .eq(typeEq != null, Receipt::getReceiptType, typeEq)
                                .eq(dirEq != null, Receipt::getDocDirection, dirEq)
                                .like(noKw != null, Receipt::getReceiptNo, noKw)
                                .in(nameIds != null, Receipt::getProjectId, nameIds)
                                .ge(dateFrom != null, Receipt::getReceiptDate, dateFrom)
                                .le(dateTo != null, Receipt::getReceiptDate, dateTo)
                                .orderByDesc(Receipt::getCreatedAt),
                        Receipt::getCreatedBy, me));
        Map<String, DictData> statusDict = dictMap(DICT_APPROVAL_STATUS);
        Map<String, DictData> businessDict = dictMap(DICT_RECEIPT_BUSINESS_STATUS);
        // V78：款项类型拆为蓝字(receipt_type) + 红字(receipt_red_type) 双字典，反查需合并
        Map<String, DictData> typeDict = new HashMap<>();
        typeDict.putAll(dictMap(DICT_RECEIPT_TYPE));
        typeDict.putAll(dictMap(DICT_RECEIPT_RED_TYPE));
        Map<String, DictData> docDict = dictMap(DICT_DOC_DIRECTION);
        Map<Long, String> userNames = userNamesOf(page.getRecords());
        // 项目信息批量预取：一页最多 500 条，逐条 selectById 会放大成 500 次查询
        Map<Long, Project> projects =
                BatchLoader.projects(projectMapper, page.getRecords(), Receipt::getProjectId);
        // V72：客户名按 customer_id 批量翻译（fin_project 已删该列）
        Map<Long, String> customerNames = BatchLoader.customerNames(customerMapper, projects.values());
        return page.convert(r -> toVO(r, statusDict, businessDict, typeDict, docDict, userNames, projects, customerNames));
    }

    @Override
    public ReceiptVO detail(Long id, String username, boolean isAdmin) {
        Receipt r = receiptMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("收款单不存在：" + id);
        }
        // 可见性三选一：申请人本人（createdBy）/ ROLE_ADMIN / 审批任务处理人（候选人）
        if (!isAdmin && !Objects.equals(r.getCreatedBy(), currentUserId())
                && !engine.isCandidate(RECEIPT_PROCESS_CODE, String.valueOf(id), username)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收款单不存在或无权访问：" + id);
        }
        Map<String, DictData> statusDict = dictMap(DICT_APPROVAL_STATUS);
        Map<String, DictData> businessDict = dictMap(DICT_RECEIPT_BUSINESS_STATUS);
        // V78：款项类型拆为蓝字(receipt_type) + 红字(receipt_red_type) 双字典，反查需合并
        Map<String, DictData> typeDict = new HashMap<>();
        typeDict.putAll(dictMap(DICT_RECEIPT_TYPE));
        typeDict.putAll(dictMap(DICT_RECEIPT_RED_TYPE));
        Map<String, DictData> docDict = dictMap(DICT_DOC_DIRECTION);
        Map<Long, String> userNames = userNamesOf(List.of(r));
        Map<Long, Project> projects =
                BatchLoader.projects(projectMapper, List.of(r), Receipt::getProjectId);
        // V72：客户名按 customer_id 批量翻译（fin_project 已删该列）
        Map<Long, String> customerNames = BatchLoader.customerNames(customerMapper, projects.values());
        ReceiptVO vo = toVO(r, statusDict, businessDict, typeDict, docDict, userNames, projects, customerNames);
        // 附件 ID 仅详情回填：放在 toVO 里会被分页放大成 N+1（一页最多 500 次单条查询）
        vo.setAttachmentFileIds(loadAttachmentIds(id));
        // 核销分配明细仅详情回填（同附件口径，V65）
        vo.setAllocations(allocationVOs(id));
        // 红冲原单号仅详情回填（N+1 防护同附件口径）
        if (r.getOriginalReceiptId() != null) {
            Receipt original = receiptMapper.selectById(r.getOriginalReceiptId());
            if (original != null) {
                vo.setOriginalReceiptNo(original.getReceiptNo());
            }
        }
        return vo;
    }

    @Override
    public List<TimelineNodeVO> timeline(Long id) {
        // processKey 必传：businessKey 跨表相撞（收款单 #2 = 发票 #2），不带类型过滤会串台
        return engine.listTimelineByBusinessKey(RECEIPT_PROCESS_CODE, String.valueOf(id));
    }

    /**
     * 事后核销分配（2026-10-25 虎哥拍板 A）：全量替换 + 差额滚动，单事务。
     *
     * <p>执行序：① 旧行中「消失或金额变化」的置 REVERSED 留痕（不物理删，V1 范式）并
     * 回滚应收单 received/unreceived；② 新行/金额变化行 insert NORMAL 并滚入；
     * ③ 同步本单 written_off/unwritten_off（V1 列，V65 首次写、此处覆盖旧值）。</p>
     *
     * <p>取舍说明：反核销用「原行置 REVERSED」而非 V1 注释的「生成反向新行 +
     * original_allocation_id 链」——REVERSED 行自带 amount/receivable_id/updated_at，
     * 足以追溯「曾经分过多少、何时被收回」；完整反向链待审计需求明确后再补。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void allocate(Long id, List<AllocationItem> items) {
        Receipt r = receiptMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("收款单不存在：" + id);
        }
        if (!"RECEIVED".equals(r.getReceiptStatus())) {
            throw new IllegalArgumentException("仅已收款（审批通过）的单据可核销分配，当前状态：" + r.getApprovalStatus());
        }
        List<AllocationItem> list = items == null ? List.of() : items;
        if (list.isEmpty()) {
            throw new IllegalArgumentException("分配明细不能为空（至少保留一行；后续将提供反核销入口）");
        }
        validateAllocationItems(id, list, r.getProjectId(), r.getReceiptAmount(), false);

        List<WriteOff> existing = writeOffMapper.selectList(new LambdaQueryWrapper<WriteOff>()
                .eq(WriteOff::getReceiptId, id)
                .eq(WriteOff::getStatus, "NORMAL"));
        Map<Long, WriteOff> oldByRecv = existing.stream()
                .collect(Collectors.toMap(WriteOff::getReceivableId, w -> w, (a, b) -> a));
        Map<Long, BigDecimal> newByRecv = list.stream()
                .collect(Collectors.toMap(AllocationItem::getReceivableId, AllocationItem::getAmount, (a, b) -> a));

        // ① 旧行中消失或金额变化的：REVERSED 留痕 + 回滚应收单数字
        for (WriteOff w : existing) {
            BigDecimal newAmt = newByRecv.get(w.getReceivableId());
            if (newAmt == null || newAmt.compareTo(w.getAmount()) != 0) {
                rollReceivable(w.getReceivableId(), w.getAmount(), false);
                w.setStatus("REVERSED");
                writeOffMapper.updateById(w);
                log.info("[receipt] 核销行反核销留痕 writeOffId={} receivableId={} amount={}",
                        w.getId(), w.getReceivableId(), w.getAmount());
            }
        }
        // ② 新行/金额变化行：insert NORMAL + 滚入应收单数字
        for (AllocationItem it : list) {
            WriteOff old = oldByRecv.get(it.getReceivableId());
            if (old == null || old.getAmount().compareTo(it.getAmount()) != 0) {
                insertAllocationRows(id, List.of(it));
                rollReceivable(it.getReceivableId(), it.getAmount(), true);
            }
        }
        // ③ 同步本单核销口径（部分分配时 unwritten 留余额）
        BigDecimal written = list.stream()
                .map(AllocationItem::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        r.setWrittenOffAmount(written);
        r.setUnwrittenOffAmount(r.getReceiptAmount().subtract(written));
        receiptMapper.updateById(r);
        log.info("[receipt] 事后核销分配完成 receiptId={} rows={} written={} unwritten={}",
                id, list.size(), written, r.getUnwrittenOffAmount());
    }

    /**
     * 滚（add）或回滚（!add）应收单回款口径：received/unreceived/writtenOff 成对增减。
     * writtenOffAmount（列表「已核销」列）此前从不回写、恒为 0，与 receivedAmount 同口径同步。
     */
    private void rollReceivable(Long receivableId, BigDecimal amount, boolean add) {
        Receivable rv = receivableMapper.selectById(receivableId);
        if (rv == null) {
            return;
        }
        BigDecimal received = rv.getReceivedAmount() == null ? BigDecimal.ZERO : rv.getReceivedAmount();
        BigDecimal unreceived = rv.getUnreceivedAmount() == null ? BigDecimal.ZERO : rv.getUnreceivedAmount();
        BigDecimal written = rv.getWrittenOffAmount() == null ? BigDecimal.ZERO : rv.getWrittenOffAmount();
        BigDecimal receivableAmt = rv.getReceivableAmount() == null ? BigDecimal.ZERO : rv.getReceivableAmount();
        rv.setReceivedAmount(add ? received.add(amount) : received.subtract(amount));
        rv.setUnreceivedAmount(add ? unreceived.subtract(amount) : unreceived.add(amount));
        // D2：应收单「已核销」= 已回款核销累计（与 receivedAmount 同口径），此前从不回写恒为 0
        rv.setWrittenOffAmount(add ? written.add(amount) : written.subtract(amount));
        rv.setUnwrittenOffAmount(receivableAmt.subtract(rv.getWrittenOffAmount()));
        receivableMapper.updateById(rv);
    }

    // ===================== 私有 =====================

    /**
     * 累加/扣减项目已收款（fin_project.collected_amount）；金额纪律：null 当 0。
     *
     * <p>docDirection=RED（红冲单）时做减法，保持冗余字段守恒可对账
     * （2026-10-25 红冲入口；实时聚合口径在 ProjectServiceImpl，此处仅维护冗余列）。</p>
     */
    private void accumulateProjectCollected(Long projectId, BigDecimal amount, String docDirection) {
        if (projectId == null || amount == null) {
            return;
        }
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            return;
        }
        boolean red = "RED".equals(docDirection);
        BigDecimal base = p.getCollectedAmount() == null ? BigDecimal.ZERO : p.getCollectedAmount();
        p.setCollectedAmount(red ? base.subtract(amount) : base.add(amount));
        projectMapper.updateById(p);
    }

    /**
     * 实时累计已收（蓝字加、红字减；与项目详情页「累计已收」口径完全一致），可排除指定收款单。
     * 刻意不读 fin_project.collected_amount 冗余列——该列历史上出现过漂移，会给出错误阈值。
     */
    private BigDecimal currentReceived(Long projectId, Long excludeReceiptId) {
        if (projectId == null) {
            return BigDecimal.ZERO;
        }
        List<Receipt> list = receiptMapper.selectList(new LambdaQueryWrapper<Receipt>()
                .select(Receipt::getReceiptAmount, Receipt::getDocDirection)
                .eq(Receipt::getProjectId, projectId)
                .eq(Receipt::getReceiptStatus, "RECEIVED")
                .ne(excludeReceiptId != null, Receipt::getId, excludeReceiptId));
        BigDecimal sum = BigDecimal.ZERO;
        for (Receipt r : list) {
            BigDecimal amt = r.getReceiptAmount() == null ? BigDecimal.ZERO : r.getReceiptAmount();
            sum = "RED".equals(r.getDocDirection()) ? sum.subtract(amt) : sum.add(amt);
        }
        return sum;
    }

    /**
     * 超收硬校验（双保险核心）：蓝字（收款）不得使「累计已收」超过合同金额；红字（退款）减少已收，直接放行。
     * 信任是系统命脉，账绝对不能算错——任何会把累计已收顶过合同的收款一律拒绝，前端亦会拦截。
     */
    private void assertWithinContract(Project project, BigDecimal incrementalAmount,
                                      String docDirection, Long excludeReceiptId) {
        if (project == null || "RED".equals(docDirection)) {
            return;
        }
        BigDecimal contract = project.getContractAmount() == null ? BigDecimal.ZERO : project.getContractAmount();
        BigDecimal already = currentReceived(project.getId(), excludeReceiptId);
        BigDecimal incoming = incrementalAmount == null ? BigDecimal.ZERO : incrementalAmount;
        BigDecimal projected = already.add(incoming);
        if (projected.compareTo(contract) > 0) {
            throw new IllegalArgumentException(String.format(
                    "收款金额超出合同可收额度：合同金额 %s，当前已收 %s，本次 %s，将超出 %s。"
                            + "请核实金额，或先对超收部分做红冲后再录。",
                    contract.toPlainString(), already.toPlainString(),
                    incoming.toPlainString(), projected.subtract(contract).toPlainString()));
        }
    }

    /**
     * 红冲单校验（2026-10-25 红冲入口）：原单必须存在、已审批通过（RECEIVED）、
     * 同项目、本单金额 ≤ 原单金额（支持部分红冲，税务同口径）。
     */
    private Receipt validateRedFlush(ReceiptRequest req, Project project) {
        if (req.getOriginalReceiptId() == null) {
            throw new IllegalArgumentException("红冲单必须指定原收款单（originalReceiptId）");
        }
        Receipt original = receiptMapper.selectById(req.getOriginalReceiptId());
        if (original == null) {
            throw new IllegalArgumentException("原收款单不存在：" + req.getOriginalReceiptId());
        }
        if (!"RECEIVED".equals(original.getReceiptStatus())) {
            throw new IllegalArgumentException("仅已收款（审批通过）的单据可红冲，原单当前状态：" + original.getApprovalStatus());
        }
        if (!Objects.equals(original.getProjectId(), project.getId())) {
            throw new IllegalArgumentException("红冲单必须与原单同项目，请新建");
        }
        if (req.getReceiptAmount().compareTo(original.getReceiptAmount()) > 0) {
            throw new IllegalArgumentException("红冲金额不能超过原单金额（" + original.getReceiptAmount().toPlainString() + "），支持部分红冲");
        }
        return original;
    }

    // ===================== 核销分配（V65 双向拆分，虎哥拍板 B） =====================

    /**
     * 校验并落库核销分配明细（计划态，不滚数字）。create/update 口径：必须 Σ=总额。
     *
     * <p>规则：① Σ amount = 主单总额；② 每行 ≤ 该应收单未回款余额扣减在途分配
     * （审批中其他单占用；已通过单的额度已滚进 unreceived_amount，不重复扣）；
     * ③ 同一应收单合并一行；④ 分配必须落在本项目下（防跨项目错配）。
     * 空列表 = 预收未指定，合法。</p>
     */
    private void saveAllocations(Long receiptId, List<AllocationItem> items, BigDecimal total, Long projectId) {
        if (items == null || items.isEmpty()) {
            return;
        }
        validateAllocationItems(receiptId, items, projectId, total, true);
        insertAllocationRows(receiptId, items);
        log.info("[receipt] 核销分配落库 receiptId={} rows={} total={}", receiptId, items.size(), total);
    }

    /**
     * 分配逐行校验（saveAllocations 计划态 / allocate 事后态共用，2026-10-25 A 方案抽出）。
     *
     * <p>requireFull=true（创建/编辑口径）：Σ 必须 = maxTotal；
     * requireFull=false（事后核销口径）：允许部分分配，Σ ≤ maxTotal，
     * 余额留在收款单 unwritten_off_amount。</p>
     */
    private void validateAllocationItems(Long receiptId, List<AllocationItem> items, Long projectId,
                                         BigDecimal maxTotal, boolean requireFull) {
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
        for (AllocationItem it : items) {
            Receivable rv = byId.get(it.getReceivableId());
            if (!Objects.equals(rv.getProjectId(), projectId)) {
                throw new IllegalArgumentException("应收单不属于本项目：" + rv.getReceivableNo());
            }
            if (it.getAmount() == null || it.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("分配金额必须为正：" + rv.getReceivableNo());
            }
            BigDecimal available = (rv.getUnreceivedAmount() == null ? BigDecimal.ZERO : rv.getUnreceivedAmount())
                    .subtract(sumInFlightAllocations(rv.getId(), receiptId));
            if (it.getAmount().compareTo(available) > 0) {
                throw new IllegalArgumentException("超出应收单未回款余额：" + rv.getReceivableNo()
                        + "，可用 " + available.toPlainString());
            }
            sum = sum.add(it.getAmount());
        }
        if (requireFull) {
            if (sum.compareTo(maxTotal) != 0) {
                throw new IllegalArgumentException("分配总额必须等于收款金额：分配 " + sum.toPlainString()
                        + " ≠ 收款 " + maxTotal.toPlainString());
            }
        } else {
            if (sum.compareTo(maxTotal) > 0) {
                throw new IllegalArgumentException("分配总额不能超过收款金额：分配 " + sum.toPlainString()
                        + " > 收款 " + maxTotal.toPlainString());
            }
        }
    }

    /** 分配明细逐行 insert（计划态；状态 NORMAL，单号 WO-yyyyMMdd-NNN count+1） */
    private void insertAllocationRows(Long receiptId, List<AllocationItem> items) {
        // V80：核销分配行带 project_id（取主单项目），支撑看板 F-08/F-09 随项目/客户筛选下推
        Long projId = receiptMapper.selectById(receiptId).getProjectId();
        for (AllocationItem it : items) {
            WriteOff w = new WriteOff();
            w.setWriteOffNo(generateWriteOffNo());
            w.setReceiptId(receiptId);
            w.setReceivableId(it.getReceivableId());
            w.setProjectId(projId);
            w.setAmount(it.getAmount());
            w.setStatus("NORMAL");
            w.setWriteOffDate(LocalDate.now());
            w.setWriteOffMethod("MANUAL");
            writeOffMapper.insert(w);
        }
    }

    /**
     * 指定应收单的「在途分配」合计：其他收款单已提交审批（APPROVING）占用但尚未
     * 生效（completeTask 未跑）的额度。已通过单的额度已滚进 unreceived_amount，不重复扣。
     */
    private BigDecimal sumInFlightAllocations(Long receivableId, Long excludeReceiptId) {
        List<WriteOff> rows = writeOffMapper.selectList(new LambdaQueryWrapper<WriteOff>()
                .eq(WriteOff::getReceivableId, receivableId)
                .eq(WriteOff::getStatus, "NORMAL"));
        Set<Long> otherReceiptIds = rows.stream()
                .map(WriteOff::getReceiptId)
                .filter(rid -> !Objects.equals(rid, excludeReceiptId))
                .collect(Collectors.toSet());
        if (otherReceiptIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<Receipt> inFlight = receiptMapper.selectList(new LambdaQueryWrapper<Receipt>()
                .in(Receipt::getId, otherReceiptIds)
                .eq(Receipt::getApprovalStatus, "APPROVING"));
        Set<Long> inFlightIds = inFlight.stream().map(Receipt::getId).collect(Collectors.toSet());
        return rows.stream()
                .filter(w -> inFlightIds.contains(w.getReceiptId()))
                .map(WriteOff::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 审批通过：核销生效。单事务内滚 ① 各应收单 received/unreceived；
     * ② 本单 written_off/unwritten_off（V1 列，V65 首次写）。
     */
    private void applyWriteOffs(Receipt r) {
        List<WriteOff> rows = writeOffMapper.selectList(new LambdaQueryWrapper<WriteOff>()
                .eq(WriteOff::getReceiptId, r.getId())
                .eq(WriteOff::getStatus, "NORMAL"));
        if (rows.isEmpty()) {
            return;
        }
        BigDecimal written = BigDecimal.ZERO;
        // RED（红冲单）反向：扣回应收单已回款、加回未回款（2026-10-25 红冲入口）
        boolean red = "RED".equals(r.getDocDirection());
        for (WriteOff w : rows) {
            Receivable rv = receivableMapper.selectById(w.getReceivableId());
            if (rv == null) {
                continue;
            }
            BigDecimal receivableAmt = rv.getReceivableAmount() == null ? BigDecimal.ZERO : rv.getReceivableAmount();
            // 目标应收单为红字（退货生成的贷项备忘）：执行「消耗」语义——累加已冲销 writtenOff、
            // 标记贷项结清；不对其 received 做加减（红字应收 received 恒为 0，减项统一由收款单
            // docDirection=RED 在 accumulateProjectCollected 层面取负）。最后一道闸：防双冲，
            // writtenOff 不得超出 receivableAmount，否则说明已被冲销过一次，阻断。
            if ("RED".equals(rv.getDocDirection())) {
                BigDecimal writtenOff = rv.getWrittenOffAmount() == null ? BigDecimal.ZERO : rv.getWrittenOffAmount();
                BigDecimal newWritten = writtenOff.add(w.getAmount());
                if (newWritten.compareTo(receivableAmt) > 0) {
                    throw new IllegalArgumentException("红字应收单 " + rv.getReceivableNo()
                            + " 冲销金额超出可冲销余额（已冲销 " + writtenOff.toPlainString()
                            + "，本次 " + w.getAmount().toPlainString()
                            + "，应收额 " + receivableAmt.toPlainString() + "），疑似重复冲销，已阻断。");
                }
                rv.setWrittenOffAmount(newWritten);
                rv.setUnwrittenOffAmount(receivableAmt.subtract(newWritten));
                receivableMapper.updateById(rv);
                written = written.add(w.getAmount());
                continue;
            }
            // 目标应收单为蓝字：按收款单方向加减（蓝字收款单 → add；红冲单 → subtract 反向）
            BigDecimal received = rv.getReceivedAmount() == null ? BigDecimal.ZERO : rv.getReceivedAmount();
            BigDecimal unreceived = rv.getUnreceivedAmount() == null ? BigDecimal.ZERO : rv.getUnreceivedAmount();
            BigDecimal writtenOff = rv.getWrittenOffAmount() == null ? BigDecimal.ZERO : rv.getWrittenOffAmount();
            rv.setReceivedAmount(red ? received.subtract(w.getAmount()) : received.add(w.getAmount()));
            rv.setUnreceivedAmount(red ? unreceived.add(w.getAmount()) : unreceived.subtract(w.getAmount()));
            // D2：应收单「已核销」同步（与 receivedAmount 同口径，红冲反向）
            rv.setWrittenOffAmount(red ? writtenOff.subtract(w.getAmount()) : writtenOff.add(w.getAmount()));
            rv.setUnwrittenOffAmount(receivableAmt.subtract(rv.getWrittenOffAmount()));
            receivableMapper.updateById(rv);
            written = written.add(w.getAmount());
        }
        r.setWrittenOffAmount(written);
        r.setUnwrittenOffAmount(r.getReceiptAmount().subtract(written));
        receiptMapper.updateById(r);
        log.info("[receipt] 核销生效 receiptId={} rows={} writtenOff={}", r.getId(), rows.size(), written);
    }

    /** 分配明细 → VO（detail 回填，含应收单号） */
    private List<AllocationVO> allocationVOs(Long receiptId) {
        List<WriteOff> rows = writeOffMapper.selectList(new LambdaQueryWrapper<WriteOff>()
                .eq(WriteOff::getReceiptId, receiptId)
                .eq(WriteOff::getStatus, "NORMAL"));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, String> nos = receivableMapper.selectList(new LambdaQueryWrapper<Receivable>()
                        .in(Receivable::getId,
                                rows.stream().map(WriteOff::getReceivableId).collect(Collectors.toSet())))
                .stream().collect(Collectors.toMap(Receivable::getId, Receivable::getReceivableNo));
        List<AllocationVO> vos = new ArrayList<>(rows.size());
        for (WriteOff w : rows) {
            AllocationVO vo = new AllocationVO();
            vo.setReceivableId(w.getReceivableId());
            vo.setReceivableNo(nos.get(w.getReceivableId()));
            vo.setAmount(w.getAmount());
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 核销单号 WO-yyyyMMdd-NNN：取【含逻辑删除行】的当日最大序号 +1（同 generateReceiptNo 范式）。
     *
     * <p>⚠️ 不能用 BaseMapper.selectCount + 1：MP 逻辑删除拦截器会追加 is_deleted=0，
     * 而 uk_tenant_write_off_no(tenant_id, write_off_no) 唯一键不区分 is_deleted。
     * 收款单 update(:309) / withdraw(:341) 都是「软删旧核销行 → 重新生成单号」，
     * 用 count 会复用刚软删的号撞唯一键（与发票 IA- 同款故障，2026-10-31 一并修复）。</p>
     */
    private String generateWriteOffNo() {
        String date = LocalDateTime.now().format(RECEIPT_NO_DATE_FMT);
        Integer maxSeq = writeOffMapper.selectMaxDailySeq(date);
        int next = (maxSeq == null ? 0 : maxSeq) + 1;
        return String.format("WO-%s-%03d", date, next);
    }

    /** 查 ENABLED 的收款审批流程定义（与 ProjectServiceImpl.submit 同范式） */
    private ProcessDef processDefMapperSafe() {
        return processDefMapper.selectOne(new LambdaQueryWrapper<ProcessDef>()
                .eq(ProcessDef::getProcessCode, RECEIPT_PROCESS_CODE)
                .eq(ProcessDef::getStatus, "ENABLED"));
    }

    private ReceiptVO toVO(Receipt r, Map<String, DictData> statusDict, Map<String, DictData> businessDict,
                           Map<String, DictData> typeDict, Map<String, DictData> docDict,
                           Map<Long, String> userNames, Map<Long, Project> projects, Map<Long, String> customerNames) {
        ReceiptVO vo = new ReceiptVO();
        vo.setId(r.getId());
        vo.setReceiptNo(r.getReceiptNo());
        vo.setProjectId(r.getProjectId());
        vo.setReceiptAmount(r.getReceiptAmount());
        vo.setDocDirection(r.getDocDirection());
        DictData doc = docDict.get(r.getDocDirection());
        vo.setDocDirectionName(doc != null ? doc.getDictLabel() : null);
        vo.setDocDirectionStyle(doc != null ? doc.getStyle() : null);
        vo.setReceiptType(r.getReceiptType());
        DictData type = typeDict.get(r.getReceiptType());
        vo.setReceiptTypeName(type != null ? type.getDictLabel() : null);
        vo.setReceiptTypeStyle(type != null ? type.getStyle() : null);
        vo.setReceiptDate(r.getReceiptDate());
        vo.setBankReceiptPath(r.getBankReceiptPath());
        vo.setApprovalStatus(r.getApprovalStatus());
        DictData st = statusDict.get(r.getApprovalStatus());
        vo.setApprovalStatusName(st != null ? st.getDictLabel() : null);
        vo.setApprovalStatusStyle(st != null ? st.getStyle() : null);
        vo.setReceiptStatus(r.getReceiptStatus());
        DictData bs = businessDict.get(r.getReceiptStatus());
        vo.setReceiptStatusName(bs != null ? bs.getDictLabel() : null);
        vo.setReceiptStatusStyle(bs != null ? bs.getStyle() : null);
        vo.setProcessInstanceId(r.getProcessInstanceId());
        vo.setApplicantId(r.getCreatedBy());
        vo.setApplicantName(r.getCreatedBy() != null ? userNames.get(r.getCreatedBy()) : null);
        vo.setCreatedAt(r.getCreatedAt());

        // 项目信息由调用方批量预取传入（避免逐条 selectById 的 N+1）
        Project p = r.getProjectId() == null ? null : projects.get(r.getProjectId());
        if (p != null) {
            vo.setProjectNo(p.getProjectNo());
            vo.setProjectName(p.getProjectName());
            // V72：客户名按 customer_id 翻译（fin_project 已删该列）
            vo.setCustomerName(customerNames.get(p.getCustomerId()));
        }
        // 详情页「通过/驳回」切换：审批中才有待办任务；同时回填当前节点名
        if (r.getProcessInstanceId() != null && "APPROVING".equals(r.getApprovalStatus())) {
            List<TaskInfo> tasks = engine.listTasks(r.getProcessInstanceId());
            if (tasks != null && !tasks.isEmpty()) {
                vo.setMyPendingTaskId(tasks.get(0).getTaskId());
                // V75：只有多节点流程才回填「当前节点名」——单节点流程（发货/收款/发票）
                // 没有「第几级」概念，回填的 taskName 恒等于流程名，纯噪音
                if (FlowNodeUtil.isMultiNode(processDefMapper, RECEIPT_PROCESS_CODE)) {
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

    private Map<Long, String> userNamesOf(List<Receipt> receipts) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Receipt r : receipts) {
            if (r.getCreatedBy() != null) ids.add(r.getCreatedBy());
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

    private String generateReceiptNo() {
        String date = LocalDateTime.now().format(RECEIPT_NO_DATE_FMT);
        // 关键：取含逻辑删除行的当天最大序号 +1。
        // 逻辑删除行仍物理占用 uk_tenant_receipt_no 唯一键，必须纳入，否则会复用已删单号撞键 500。
        Integer maxSeq = receiptMapper.selectMaxDailySeq(date);
        int next = (maxSeq == null ? 0 : maxSeq) + 1;
        return String.format("SK-%s-%03d", date, next);
    }

    /** 提交时通知出纳（ROLE_CASHIER，模板 RECEIPT_AUDIT） */
    private void notifySubmitted(Receipt r, String code) {
        List<Long> receivers = resolveRoleUserIds(ROLE_CASHIER);
        if (receivers.isEmpty()) {
            log.warn("[receipt] 提交审批通知无接收人：role={} 下无用户，请检查 sys_user_role 角色绑定 receiptId={}",
                    ROLE_CASHIER, r.getId());
            return;
        }
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode(code);
        cmd.setReceivers(receivers);
        cmd.setBizType("RECEIPT");
        cmd.setBizId(String.valueOf(r.getId()));
        cmd.setLinkUrl("/approval");
        cmd.setDedupKey(code + ":RECEIPT:" + r.getId());
        cmd.setVars(Map.of(
                "applicant", userName(r.getCreatedBy()),
                "amount", r.getReceiptAmount() != null ? r.getReceiptAmount().toPlainString() : ""));
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    /** 审批通过/驳回通知申请人（模板 code） */
    private void notifyResult(Receipt r, String code, String result, String comment) {
        Long applicantId = r.getCreatedBy();
        if (applicantId == null) {
            return;
        }
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode(code);
        cmd.setReceivers(List.of(applicantId));
        cmd.setBizType("RECEIPT");
        cmd.setBizId(String.valueOf(r.getId()));
        cmd.setLinkUrl("/receipt");
        cmd.setDedupKey(code + ":RECEIPT:" + r.getId());
        cmd.setVars(Map.of(
                "applicant", userName(r.getCreatedBy()),
                "amount", r.getReceiptAmount() != null ? r.getReceiptAmount().toPlainString() : ""));
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    /** 撤回通知（模板 RECEIPT_AUDIT；无人审批过则不发） */
    private void notifyWithdrawn(Receipt r, List<Long> approvedUserIds) {
        if (approvedUserIds == null || approvedUserIds.isEmpty()) {
            log.debug("[notify] 撤回时尚无人审批，跳过通知 receiptId={}", r.getId());
            return;
        }
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("RECEIPT_AUDIT");
        cmd.setReceivers(approvedUserIds);
        cmd.setBizType("RECEIPT");
        cmd.setBizId(String.valueOf(r.getId()));
        cmd.setLinkUrl("/receipt");
        cmd.setDedupKey("RECEIPT_AUDIT:RECEIPT:" + r.getId());
        cmd.setVars(Map.of(
                "applicant", userName(r.getCreatedBy()),
                "amount", r.getReceiptAmount() != null ? r.getReceiptAmount().toPlainString() : ""));
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    /** 角色 code → 全员 userId（复用 ShipmentServiceImpl.resolveRoleUserIds 范式） */
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

    private String userName(Long userId) {
        if (userId == null) {
            return "";
        }
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getId, userId).select(User::getUsername));
        return u == null ? String.valueOf(userId) : u.getUsername();
    }

    // ===================== 附件软关联（2026-10-25 V62 真上传） =====================

    /**
     * 单据落库后挂载附件：把前端上传时（单据尚无 id）没带 bizId 的文件改写 bizType/bizId。
     *
     * <p>与发货签收证据同构（V54）——附件不落在业务表列上，只存在于 sys_file_storage。
     * bizType 固定 {@link #RECEIPT_ATTACH_BIZ_TYPE}，与 file_biz_type 字典 value 一致。</p>
     */
    private void bindAttachments(Long receiptId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty() || receiptId == null) {
            return;
        }
        for (Long fid : fileIds) {
            FileStorage f = fileStorageMapper.selectById(fid);
            if (f == null) {
                continue;
            }
            f.setBizType(RECEIPT_ATTACH_BIZ_TYPE);
            f.setBizId(receiptId);
            fileStorageMapper.updateById(f);
        }
    }

    /**
     * 编辑态重新挂载：以本次提交的全量 fileIds 为准。
     *
     * <p>先挂载（新上传的），再把「已属于本单但不在本次列表里」的文件解绑（bizId 置 NULL）——
     * 前端删除附件时不调删除接口（文件本身保留在库），只是在表单里移除，故这里做差分解绑。</p>
     */
    private void rebindAttachments(Long receiptId, List<Long> fileIds) {
        bindAttachments(receiptId, fileIds);
        List<Long> keep = fileIds == null ? List.of() : fileIds;
        List<FileStorage> bound = fileStorageMapper.selectList(new LambdaQueryWrapper<FileStorage>()
                .eq(FileStorage::getBizType, RECEIPT_ATTACH_BIZ_TYPE)
                .eq(FileStorage::getBizId, receiptId));
        for (FileStorage f : bound) {
            if (!keep.contains(f.getId())) {
                f.setBizId(null);
                fileStorageMapper.updateById(f);
            }
        }
    }

    /** 按 bizType+bizId 回查附件 ID 列表（顺序稳定：按文件主键升序）。 */
    private List<Long> loadAttachmentIds(Long receiptId) {
        List<FileStorage> files = fileStorageMapper.selectList(new LambdaQueryWrapper<FileStorage>()
                .eq(FileStorage::getBizType, RECEIPT_ATTACH_BIZ_TYPE)
                .eq(FileStorage::getBizId, receiptId)
                .orderByAsc(FileStorage::getId));
        return files.stream().map(FileStorage::getId).collect(Collectors.toList());
    }
}
