package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.annotation.Auditable;
import com.firestar.common.BatchLoader;
import com.firestar.common.DataScopeHelper;
import com.firestar.dto.NotifyCommand;
import com.firestar.dto.ReceiptRequest;
import com.firestar.dto.ReceiptVO;
import com.firestar.dto.TaskInfo;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.entity.DictData;
import com.firestar.entity.Project;
import com.firestar.entity.Receipt;
import com.firestar.entity.Role;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.entity.ProcessDef;
import com.firestar.mapper.DictDataMapper;
import com.firestar.mapper.ProcessDefMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceiptMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
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
import java.time.LocalDateTime;
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

    private static final DateTimeFormatter RECEIPT_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String DICT_RECEIPT_STATUS = "receipt_status";
    private static final String DICT_RECEIPT_TYPE = "receipt_type";
    private static final String DICT_DOC_DIRECTION = "doc_direction";

    private final ReceiptMapper receiptMapper;
    private final ProjectMapper projectMapper;
    private final DictDataMapper dictDataMapper;
    private final ProcessDefMapper processDefMapper;
    private final WorkflowEngineService engine;
    private final NotificationService notificationService;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;

    @Autowired
    public ReceiptServiceImpl(ReceiptMapper receiptMapper, ProjectMapper projectMapper,
                              DictDataMapper dictDataMapper, ProcessDefMapper processDefMapper,
                              WorkflowEngineService engine, NotificationService notificationService,
                              UserMapper userMapper, RoleMapper roleMapper, UserRoleMapper userRoleMapper) {
        this.receiptMapper = receiptMapper;
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
    @Auditable(operationType = "CREATE", targetType = "RECEIPT", targetIdSpEl = "#result",
            entityClass = Receipt.class,
            fields = {"projectId", "receiptAmount", "receiptType", "receiptDate", "docDirection"})
    public Long create(ReceiptRequest req) {
        Long operatorId = currentUserId();
        if (req.getProjectId() == null) {
            throw new IllegalArgumentException("项目不能为空");
        }
        if (req.getReceiptAmount() == null || req.getReceiptAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("收款金额必须为正");
        }
        Project project = projectMapper.selectById(req.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("项目不存在：" + req.getProjectId());
        }

        Receipt receipt = new Receipt();
        receipt.setProjectId(project.getId());
        receipt.setReceiptAmount(req.getReceiptAmount());
        receipt.setReceiptType(req.getReceiptType());
        receipt.setReceiptDate(req.getReceiptDate());
        receipt.setBankReceiptPath(req.getBankReceiptPath());
        // docDirection 默认 BLUE（蓝字）
        receipt.setDocDirection("BLUE");
        // 创建即草稿，待提交再拉审批
        receipt.setApprovalStatus("PENDING_APPROVAL");
        receipt.setReceiptNo(generateReceiptNo());
        // 归属人 = createdBy（LogicDeleteEntity 父类列）
        receipt.setCreatedBy(operatorId);
        receiptMapper.insert(receipt);

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
        // 仅草稿态可提交
        if (!"PENDING_APPROVAL".equals(r.getApprovalStatus())) {
            throw new IllegalArgumentException("仅草稿态可提交审批，当前：" + r.getApprovalStatus());
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
        r.setApprovalStatus("CASHIER_APPROVING");
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
        // 仅草稿态可编辑；提交后进入审批链，不可改金额
        if (!"PENDING_APPROVAL".equals(r.getApprovalStatus())) {
            throw new IllegalArgumentException("仅草稿态可编辑，当前：" + r.getApprovalStatus());
        }
        if (req.getProjectId() == null) {
            throw new IllegalArgumentException("项目不能为空");
        }
        Project project = projectMapper.selectById(req.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("项目不存在：" + req.getProjectId());
        }
        if (req.getReceiptAmount() == null || req.getReceiptAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("收款金额必须为正");
        }
        // 不允许更换项目（换项目等于换合同，应新建；且汇总列是项目维度的）
        if (!Objects.equals(req.getProjectId(), r.getProjectId())) {
            throw new IllegalArgumentException("不允许更换项目，请新建收款单");
        }
        r.setReceiptAmount(req.getReceiptAmount());
        r.setReceiptType(req.getReceiptType());
        r.setReceiptDate(req.getReceiptDate());
        r.setBankReceiptPath(req.getBankReceiptPath());
        r.setUpdatedBy(operatorId);
        receiptMapper.updateById(r);
        log.info("[receipt] 编辑收款单 id={} amount={} user={}", id, req.getReceiptAmount(), currentUsername());
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
                "申请人撤回收款申请，收款单 id=" + id);
        r.setApprovalStatus("CANCELLED");
        r.setUpdatedBy(currentUserId());
        receiptMapper.updateById(r);
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
                r.setApprovalStatus("RECEIVED");
                receiptMapper.updateById(r);
                // 累加 fin_project.collected_amount（金额纪律：null 当 0，add）
                accumulateProjectCollected(r.getProjectId(), r.getReceiptAmount());
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
                                 Long projectId, String status) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 500L);
        String statusEq = status == null || status.isBlank() ? null : status.trim();

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
                                .orderByDesc(Receipt::getCreatedAt),
                        Receipt::getCreatedBy, me));
        Map<String, DictData> statusDict = dictMap(DICT_RECEIPT_STATUS);
        Map<String, DictData> typeDict = dictMap(DICT_RECEIPT_TYPE);
        Map<String, DictData> docDict = dictMap(DICT_DOC_DIRECTION);
        Map<Long, String> userNames = userNamesOf(page.getRecords());
        // 项目信息批量预取：一页最多 500 条，逐条 selectById 会放大成 500 次查询
        Map<Long, Project> projects =
                BatchLoader.projects(projectMapper, page.getRecords(), Receipt::getProjectId);
        return page.convert(r -> toVO(r, statusDict, typeDict, docDict, userNames, projects));
    }

    @Override
    public ReceiptVO detail(Long id, String username, boolean isAdmin) {
        Receipt r = receiptMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("收款单不存在：" + id);
        }
        // 可见性三选一：申请人本人（createdBy）/ ROLE_ADMIN / 审批任务处理人（候选人）
        if (!isAdmin && !Objects.equals(r.getCreatedBy(), currentUserId())
                && !engine.isCandidate(String.valueOf(id), username)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收款单不存在或无权访问：" + id);
        }
        Map<String, DictData> statusDict = dictMap(DICT_RECEIPT_STATUS);
        Map<String, DictData> typeDict = dictMap(DICT_RECEIPT_TYPE);
        Map<String, DictData> docDict = dictMap(DICT_DOC_DIRECTION);
        Map<Long, String> userNames = userNamesOf(List.of(r));
        Map<Long, Project> projects =
                BatchLoader.projects(projectMapper, List.of(r), Receipt::getProjectId);
        return toVO(r, statusDict, typeDict, docDict, userNames, projects);
    }

    @Override
    public List<TimelineNodeVO> timeline(Long id) {
        return engine.listTimelineByBusinessKey(String.valueOf(id));
    }

    // ===================== 私有 =====================

    /** 累加项目已收款（fin_project.collected_amount）；金额纪律：null 当 0，add */
    private void accumulateProjectCollected(Long projectId, BigDecimal amount) {
        if (projectId == null || amount == null) {
            return;
        }
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            return;
        }
        BigDecimal base = p.getCollectedAmount() == null ? BigDecimal.ZERO : p.getCollectedAmount();
        p.setCollectedAmount(base.add(amount));
        projectMapper.updateById(p);
    }

    /** 查 ENABLED 的收款审批流程定义（与 ProjectServiceImpl.submit 同范式） */
    private ProcessDef processDefMapperSafe() {
        return processDefMapper.selectOne(new LambdaQueryWrapper<ProcessDef>()
                .eq(ProcessDef::getProcessCode, RECEIPT_PROCESS_CODE)
                .eq(ProcessDef::getStatus, "ENABLED"));
    }

    private ReceiptVO toVO(Receipt r, Map<String, DictData> statusDict, Map<String, DictData> typeDict,
                           Map<String, DictData> docDict, Map<Long, String> userNames,
                           Map<Long, Project> projects) {
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
        vo.setProcessInstanceId(r.getProcessInstanceId());
        vo.setApplicantId(r.getCreatedBy());
        vo.setApplicantName(r.getCreatedBy() != null ? userNames.get(r.getCreatedBy()) : null);
        vo.setCreatedAt(r.getCreatedAt());

        // 项目信息由调用方批量预取传入（避免逐条 selectById 的 N+1）
        Project p = r.getProjectId() == null ? null : projects.get(r.getProjectId());
        if (p != null) {
            vo.setProjectNo(p.getProjectNo());
            vo.setProjectName(p.getProjectName());
            vo.setCustomerName(p.getCustomerName());
        }
        // 详情页「通过/驳回」切换：审批中才有待办任务
        if (r.getProcessInstanceId() != null && "CASHIER_APPROVING".equals(r.getApprovalStatus())) {
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
        Long count = receiptMapper.selectCount(new LambdaQueryWrapper<Receipt>()
                .likeRight(Receipt::getReceiptNo, "SK-" + date + "-"));
        return String.format("SK-%s-%03d", date, (count == null ? 0 : count) + 1);
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
}
