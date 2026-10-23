package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.annotation.Auditable;
import com.firestar.dto.ApprovalHistoryVO;
import com.firestar.dto.ItemVO;
import com.firestar.dto.NodeCfg;
import com.firestar.dto.NodeRef;
import com.firestar.dto.NotifyCommand;
import com.firestar.dto.ProjectItemReq;
import com.firestar.dto.ProjectRequest;
import com.firestar.dto.ProjectSignRequest;
import com.firestar.dto.ProjectVO;
import com.firestar.dto.TaskInfo;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.entity.DictData;
import com.firestar.entity.Item;
import com.firestar.entity.ProcessDef;
import com.firestar.entity.Project;
import com.firestar.entity.User;
import com.firestar.mapper.DictDataMapper;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.ProcessDefMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ShipmentMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.entity.Role;
import com.firestar.entity.Shipment;
import com.firestar.entity.UserRole;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.NotificationService;
import com.firestar.service.ProjectService;
import com.firestar.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
 * 项目立项服务实现（Step 3 流程编排入口）。
 *
 * <p>状态机（字典 project_approval_status）：
 * DRAFT --submit--> APPROVING --(③④ 审批动作)--> APPROVED / REJECTED --submit--> APPROVING。
 * projectNo 仅在审批通过时赋予（需求文档 §立项审批规则）。</p>
 */
@Slf4j
@Service
public class ProjectServiceImpl implements ProjectService {

    /** 立项流程定义的 process_code（V41 种子预置） */
    public static final String INITIATE_PROCESS_CODE = "project_initiate";

    /** 发货审批流程定义的 process_code（V50 种子；2026-10-23 审批中心通用化 enrich 用） */
    public static final String SHIPMENT_PROCESS_CODE = "shipment_approval";

    /** 时间线「流程结束」合成节点的 nodeId（非真实 Flowable 节点，前端据此走专属渲染） */
    private static final String END_NODE_ID = "__END__";

    /** 审批状态字典类型（V1 基线已预置 6 项） */
    private static final String DICT_APPROVAL_STATUS = "project_approval_status";

    /** 分页默认每页条数（同 RoleServiceImpl / ProcessDefServiceImpl 范式） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    /** 单页上限，与 MybatisPlusConfig.MAX_PAGE_SIZE 对齐 */
    private static final long MAX_PAGE_SIZE = 500L;

    /** 项目编号日期格式（PJ-{yyyyMMdd}-{id}） */
    private static final DateTimeFormatter PROJECT_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ProjectMapper mapper;
    private final ProcessDefMapper processDefMapper;
    private final DictDataMapper dictDataMapper;
    private final WorkflowEngineService engine;
    private final NotificationService notificationService;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final ShipmentMapper shipmentMapper;
    private final ItemMapper itemMapper;

    @Autowired
    public ProjectServiceImpl(ProjectMapper mapper, ProcessDefMapper processDefMapper,
                              DictDataMapper dictDataMapper, WorkflowEngineService engine,
                              NotificationService notificationService, UserMapper userMapper,
                              RoleMapper roleMapper, UserRoleMapper userRoleMapper,
                              ShipmentMapper shipmentMapper, ItemMapper itemMapper) {
        this.mapper = mapper;
        this.processDefMapper = processDefMapper;
        this.dictDataMapper = dictDataMapper;
        this.engine = engine;
        this.notificationService = notificationService;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.shipmentMapper = shipmentMapper;
        this.itemMapper = itemMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ProjectRequest req) {
        if (req.getProjectName() == null || req.getProjectName().isBlank()) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        if (req.getContractAmount() != null && req.getContractAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("合同总金额不能为负");
        }
        Project p = new Project();
        p.setProjectName(req.getProjectName().trim());
        p.setCustomerName(req.getCustomerName());
        p.setSalesManagerId(req.getSalesManagerId());
        // 签约后置（2026-10-22 虎哥拍板）：建项不再选合同状态，强制未签约；
        // 签约是审批通过后的独立动作（sign()），表单只认项目名称/客户/销售经理/预估金额
        p.setContractStatus("UNSIGNED");
        p.setContractAmount(req.getContractAmount());
        // 编号不在此赋予（审批通过才赋）；状态从草稿开始
        p.setApprovalStatus("DRAFT");
        // 审批发起人（V47：审批中心「我发起的」过滤）。AuditMetaObjectHandler 不填 createdBy
        // （登录模块未实现的既有决策），applicantUsername 是唯一切实的发起人来源；
        // submit 时再兜一次，防历史草稿（本改动前创建）提交后列为空
        p.setApplicantUsername(currentUsername());
        mapper.insert(p);
        // 方案2：立项带行 —— 草稿即落货物明细（审批通过后随项目生效，发货引用）
        persistItems(p.getId(), req.getItems());
        log.info("[project] 立项草稿 id={} name={}", p.getId(), p.getProjectName());
        return p.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ProjectRequest req) {
        Project exist = mapper.selectById(id);
        if (exist == null) {
            throw new IllegalArgumentException("项目不存在：" + id);
        }
        // 仅草稿 / 已驳回态可编辑；审批中（APPROVING）及以后锁死，防流程进行中被改单
        if (!"DRAFT".equals(exist.getApprovalStatus()) && !"REJECTED".equals(exist.getApprovalStatus())) {
            throw new IllegalArgumentException("当前状态不可编辑：" + exist.getApprovalStatus());
        }
        if (req.getProjectName() != null && !req.getProjectName().isBlank()) {
            exist.setProjectName(req.getProjectName().trim());
        }
        if (req.getCustomerName() != null) exist.setCustomerName(req.getCustomerName());
        if (req.getSalesManagerId() != null) exist.setSalesManagerId(req.getSalesManagerId());
        // contractStatus 不再随编辑变更（V46 起由 sign() 独占驱动）
        if (req.getContractAmount() != null) exist.setContractAmount(req.getContractAmount());
        // 方案2：立项带行 —— 草稿/驳回态重建货物明细（先逻辑删旧行，再插新行，保持幂等）
        deleteItems(exist.getId());
        persistItems(exist.getId(), req.getItems());
        mapper.updateById(exist);
    }

    // ===== 方案2：立项带行 明细持久化辅助 =====

    /** 逻辑删除某项目的全部货物明细（fin_item 是二层逻辑删除表，delete 改写 is_deleted） */
    private void deleteItems(Long projectId) {
        itemMapper.delete(new LambdaQueryWrapper<Item>().eq(Item::getProjectId, projectId));
    }

    /** 把请求里的明细行落库为 fin_item（校验 + 算行金额）；空列表则跳过 */
    private void persistItems(Long projectId, List<ProjectItemReq> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (ProjectItemReq it : items) {
            if (it.getItemName() == null || it.getItemName().isBlank()) {
                throw new IllegalArgumentException("货物名称不能为空");
            }
            if (it.getQuantity() == null || it.getQuantity() <= 0) {
                throw new IllegalArgumentException("货物「" + it.getItemName().trim() + "」合同数量必须大于 0");
            }
            Item entity = new Item();
            entity.setProjectId(projectId);
            entity.setItemName(it.getItemName().trim());
            entity.setSpecModel(it.getSpecModel());
            entity.setUnit(it.getUnit());
            entity.setQuantity(it.getQuantity());
            entity.setShippedQty(0);
            // 单价可空；行金额 = 数量 × 单价（均非空才计算）
            entity.setUnitPrice(it.getUnitPrice());
            if (it.getUnitPrice() != null) {
                entity.setTotalAmount(it.getUnitPrice().multiply(BigDecimal.valueOf(it.getQuantity())));
            }
            itemMapper.insert(entity);
        }
    }

    /** 按项目拉取（非软删）货物明细，转 ItemVO 供展示/编辑回填 */
    private List<ItemVO> loadItems(Long projectId) {
        List<Item> rows = itemMapper.selectList(new LambdaQueryWrapper<Item>()
                .eq(Item::getProjectId, projectId).orderByAsc(Item::getId));
        List<ItemVO> vos = new ArrayList<>(rows.size());
        for (Item r : rows) {
            ItemVO v = new ItemVO();
            v.setId(r.getId());
            v.setProjectId(r.getProjectId());
            v.setItemName(r.getItemName());
            v.setSpecModel(r.getSpecModel());
            v.setUnit(r.getUnit());
            v.setQuantity(r.getQuantity());
            v.setShippedQty(r.getShippedQty());
            v.setUnitPrice(r.getUnitPrice());
            v.setTotalAmount(r.getTotalAmount());
            vos.add(v);
        }
        return vos;
    }

    @Override
    public ProjectVO detail(Long id) {
        Project p = mapper.selectById(id);
        if (p == null) {
            throw new IllegalArgumentException("项目不存在：" + id);
        }
        ProjectVO vo = toVO(p, approvalStatusDict(),
                userDisplayNames(p.getSalesManagerId() == null
                        ? Collections.emptyList() : List.of(p.getSalesManagerId())));
        // 列表页不填充 items（避 N+1），仅详情回填（编辑/展示用）
        vo.setItems(loadItems(p.getId()));
        return vo;
    }

    @Override
    public IPage<ProjectVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                                 String projectNo, String projectName, String approvalStatus) {
        // 数据行级隔离（2026-10-22 虎哥）：非 ROLE_ADMIN 只能看 applicantUsername = 自己的
        // 立项（含 DRAFT 草稿——本页即「立项管理」，用户管理自己的草稿）；ROLE_ADMIN 看全部。
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String noKw = projectNo == null || projectNo.isBlank() ? null : projectNo.trim();
        String nameKw = projectName == null || projectName.isBlank() ? null : projectName.trim();
        String statusEq = approvalStatus == null || approvalStatus.isBlank() ? null : approvalStatus.trim();
        IPage<Project> page = mapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<Project>()
                        .like(noKw != null, Project::getProjectNo, noKw)
                        .like(nameKw != null, Project::getProjectName, nameKw)
                        .eq(statusEq != null, Project::getApprovalStatus, statusEq)
                        .eq(!viewAll, Project::getApplicantUsername, username)
                        .orderByDesc(Project::getCreatedAt));
        Map<String, DictData> approvalDict = approvalStatusDict();
        // 用户展示名批量翻译：销售经理 + 签约人（V46）合并 id 集合一页一次查询，避免 N+1
        Set<Long> userIds = page.getRecords().stream()
                .flatMap(p -> java.util.stream.Stream.of(p.getSalesManagerId(), p.getContractSignedBy()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> userNames = userDisplayNames(userIds);
        return page.convert(p -> toVO(p, approvalDict, userNames));
    }

    @Override
    public IPage<ProjectVO> pageMine(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                                     String projectNo, String projectName, String approvalStatus) {
        // 同 page 范式（默认 1/10、size 上限 500），追加两个条件：
        //   applicantUsername = 当前用户（V47 列，create/submit 双写 + V47 迁移回填历史）；
        //   approvalStatus != DRAFT（草稿未发起流程，不算「我发起的」）。
        // 数据行级隔离（2026-10-22 虎哥）：ROLE_ADMIN（viewAll）无人员限制看全部，
        // 但仍排除 DRAFT——豁免的是「只能看自己」的人员限制，不是「已发起」的语义限制。
        // 查询参数与 page 完全一致，前端「我发起的」Tab 直接复用 projectNo/projectName/approvalStatus。
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String noKw = projectNo == null || projectNo.isBlank() ? null : projectNo.trim();
        String nameKw = projectName == null || projectName.isBlank() ? null : projectName.trim();
        String statusEq = approvalStatus == null || approvalStatus.isBlank() ? null : approvalStatus.trim();
        IPage<Project> page = mapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<Project>()
                        .like(noKw != null, Project::getProjectNo, noKw)
                        .like(nameKw != null, Project::getProjectName, nameKw)
                        .eq(statusEq != null, Project::getApprovalStatus, statusEq)
                        .eq(!viewAll, Project::getApplicantUsername, username)
                        .ne(Project::getApprovalStatus, "DRAFT")
                        .orderByDesc(Project::getCreatedAt));
        Map<String, DictData> approvalDict = approvalStatusDict();
        Set<Long> userIds = page.getRecords().stream()
                .flatMap(p -> java.util.stream.Stream.of(p.getSalesManagerId(), p.getContractSignedBy()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> userNames = userDisplayNames(userIds);
        return page.convert(p -> toVO(p, approvalDict, userNames));
    }

    @Override
    @Auditable(operationType = "UPDATE", targetType = "PROJECT",
            targetIdSpEl = "#id", entityClass = Project.class,
            fields = {"approvalStatus", "processInstanceId"})
    public void submit(Long id) {
        Project exist = mapper.selectById(id);
        if (exist == null) {
            throw new IllegalArgumentException("项目不存在：" + id);
        }
        // DRAFT 首提，或 REJECTED 被退回后修改重报（2026-10-22 放开：
        // 此前仅放行草稿，驳回后项目卡死在「已驳回」无法重走流程）
        if (!"DRAFT".equals(exist.getApprovalStatus()) && !"REJECTED".equals(exist.getApprovalStatus())) {
            throw new IllegalArgumentException("仅草稿/已驳回状态可提交审批，当前：" + exist.getApprovalStatus());
        }
        // 方案2：立项带行 —— 提交审批前必须有货物明细，否则发货流程无合同依据（草稿态不强制）
        long itemCount = itemMapper.selectCount(new LambdaQueryWrapper<Item>()
                .eq(Item::getProjectId, exist.getId()));
        if (itemCount == 0) {
            throw new IllegalArgumentException("请先填写货物明细后再提交审批");
        }
        // 重报先终结旧流程实例：驳回时 moveActivityIdTo 后旧实例仍活着（任务退回目标节点），
        // 不终结会出现「旧实例待办 + 新实例待办」双份（2026-10-22 修）
        if (exist.getProcessInstanceId() != null && !exist.getProcessInstanceId().isBlank()) {
            engine.terminate(exist.getProcessInstanceId(), "项目重新提交审批，终结旧实例 id=" + exist.getId());
        }
        // 流程定义必须存在且启用
        ProcessDef def = processDefMapper.selectOne(new LambdaQueryWrapper<ProcessDef>()
                .eq(ProcessDef::getProcessCode, INITIATE_PROCESS_CODE)
                .eq(ProcessDef::getStatus, "ENABLED"));
        if (def == null) {
            throw new IllegalArgumentException("立项流程未配置或已停用：" + INITIATE_PROCESS_CODE);
        }
        // 业务变量：申请人（④ 通知/审计用）。skipNodeIds 由门面防御性初始化，调用方无感
        Map<String, Object> vars = new HashMap<>();
        vars.put("applicantUsername", currentUsername());
        vars.put("applicantId", exist.getSalesManagerId());
        String instanceId = engine.start(def.getProcessCode(), String.valueOf(exist.getId()), vars);

        exist.setApprovalStatus("APPROVING");
        exist.setProcessInstanceId(instanceId);
        // 兜写发起人（V47）：老草稿（applicantUsername 列回填前创建）提交时补齐，
        // 保证「我发起的」不漏单据。新草稿 create 时已写，这里是幂等覆盖
        exist.setApplicantUsername(currentUsername());
        mapper.updateById(exist);
        log.info("[project] 提交立项审批 id={} instanceId={} applicant={}",
                exist.getId(), instanceId, currentUsername());

        // ⑤ 通知：首节点处理人收到待办提醒
        notifyTodo(engine.listTasks(instanceId), exist.getProjectName());
    }

    @Override
    public List<TimelineNodeVO> listTimeline(Long id) {
        Project exist = mapper.selectById(id);
        if (exist == null) {
            throw new IllegalArgumentException("项目不存在：" + id);
        }
        if (exist.getProcessInstanceId() == null || exist.getProcessInstanceId().isBlank()) {
            return Collections.emptyList();
        }
        return appendEndNode(engine.listTimelineByBusinessKey(String.valueOf(exist.getId())), exist.getApprovalStatus());
    }

    /**
     * 流程结束标记（2026-10-22 虎哥：审批走完后历史列表底部要有一条明确交代，
     * 否则用户盯着最后一条节点犯嘀咕「结束了没」）。
     *
     * <p>仅业务终态（APPROVED / REJECTED）且至少有一个节点时追加合成节点：
     * 结束时间取末节点完成时间（流程在末节点处理后即结束）；result=ENDED，
     * handlerNames 位放结论文案（前端据此定标签色），comment 放完整描述。</p>
     */
    private List<TimelineNodeVO> appendEndNode(List<TimelineNodeVO> nodes, String approvalStatus) {
        boolean approved = "APPROVED".equals(approvalStatus);
        boolean rejected = "REJECTED".equals(approvalStatus);
        if (nodes.isEmpty() || (!approved && !rejected)) {
            return nodes;
        }
        TimelineNodeVO last = nodes.get(nodes.size() - 1);
        TimelineNodeVO end = new TimelineNodeVO();
        end.setNodeId(END_NODE_ID);
        end.setNodeName("流程结束");
        end.setResult("ENDED");
        end.setStartTime(last.getEndTime() != null ? last.getEndTime() : last.getStartTime());
        end.setEndTime(end.getStartTime());
        end.setHandlerNames(Collections.singletonList(approved ? "审批通过" : "审批未通过"));
        end.setComment(approved ? "审批通过，流程已结束，可进行合同签约"
                : "审批未通过，流程已驳回结束，可修改后重新提交");
        nodes.add(end);
        return nodes;
    }

    @Override
    @Auditable(operationType = "UPDATE", targetType = "PROJECT",
            targetIdSpEl = "#id", entityClass = Project.class,
            fields = {"contractStatus", "contractAmount", "contractDesc"},
            remark = "项目签约")
    public void sign(Long id, ProjectSignRequest req) {
        Project exist = mapper.selectById(id);
        if (exist == null) {
            throw new IllegalArgumentException("项目不存在：" + id);
        }
        // 签约前置（2026-10-22 虎哥拍板）：仅审批通过且未签约可签；终态不可撤销（a 拍板）
        if (!"APPROVED".equals(exist.getApprovalStatus())) {
            throw new IllegalArgumentException("仅审批通过的项目可签约，当前：" + exist.getApprovalStatus());
        }
        if (!"UNSIGNED".equals(exist.getContractStatus())) {
            throw new IllegalArgumentException("项目已签约，不可重复签约");
        }
        if (req.getContractAmount() != null && req.getContractAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("合同金额不能为负");
        }
        String desc = req.getContractDesc();
        if (desc != null && desc.length() > 500) {
            throw new IllegalArgumentException("签约描述不能超过 500 字");
        }
        exist.setContractStatus("SIGNED");
        // b 拍板：签约时可修正最终合同金额（建项填的是预估）；null 保持原值
        if (req.getContractAmount() != null) {
            exist.setContractAmount(req.getContractAmount());
        }
        exist.setContractDesc(desc == null ? null : desc.trim());
        exist.setContractSignedAt(LocalDateTime.now());
        exist.setContractSignedBy(currentUserId());
        mapper.updateById(exist);
        log.info("[project] 签约 id={} amount={} signedBy={}", id, exist.getContractAmount(), currentUsername());
    }

    @Override
    @Auditable(operationType = "APPROVE", targetType = "APPROVAL", targetIdSpEl = "#taskId",
            remark = "审批通过")
    public void completeTask(String taskId, Map<String, Object> variables) {
        // 审批留言从 variables 摘出（2026-10-22 虎哥：通过也留留言）：
        // 摘出后不变量化（comment 不是流程语义，只落 Flowable 评论 + 通知），
        // 同时兼容前端旧契约 {comment: ''}。
        Map<String, Object> vars = variables == null ? new HashMap<>() : new HashMap<>(variables);
        Object commentRaw = vars.remove("comment");
        String comment = commentRaw == null ? null : String.valueOf(commentRaw);
        if (comment != null && comment.isBlank()) {
            comment = null;
        }
        // 时序双保险：complete 之前先拿 businessKey（任务还在、runtime 实例必查得到）。
        // 此前在 complete 之后才查——审批通过即流程结束，runtime 实例被同步清理，
        // getBusinessKey 返回 null → 项目状态回写整段被跳过（2026-10-22 虎哥实测：
        // 「审批跑完状态没结束也没有编号」）。getBusinessKey 已同时补历史表兜底。
        String bizKeyUpfront = engine.businessKeyOfTask(taskId);
        String instanceId = engine.complete(taskId, vars, comment);
        List<TaskInfo> next = engine.listTasks(instanceId);
        if (next.isEmpty()) {
            // 流程走完（末节点审批通过）：回写项目状态 APPROVED 并赋予项目编号。
            String bizKey = bizKeyUpfront != null ? bizKeyUpfront : engine.getBusinessKey(instanceId);
            Project p = (bizKey != null) ? safeSelectProject(bizKey) : null;
            if (p != null) {
                p.setApprovalStatus("APPROVED");
                if (p.getProjectNo() == null || p.getProjectNo().isBlank()) {
                    p.setProjectNo(generateProjectNo(p.getId()));
                }
                mapper.updateById(p);
                log.info("[project] 审批通过回写 id={} projectNo={}", p.getId(), p.getProjectNo());
                notifyResult(p.getSalesManagerId(), bizKey, taskId, p.getProjectName(), "已通过", currentUsername(), comment);
            } else {
                log.warn("[project] 审批通过但未回写：bizKey={} 找不到项目 instanceId={}", bizKey, instanceId);
            }
        } else {
            // 下一节点任务通知（立项域）：标题按流程 key 分发取
            notifyTodo(next, bizTitleOf(INITIATE_PROCESS_CODE, engine.getBusinessKey(instanceId)));
        }
    }

    /**
     * 生成项目编号：PJ-{yyyyMMdd}-{id 四位补齐}（审批通过时赋予，需求文档 §立项审批规则）。
     * 日期取服务器当天；id 保证同日不重号。规则如后续要按序列走可在此单点替换。
     */
    private String generateProjectNo(Long projectId) {
        return String.format("PJ-%s-%04d", LocalDateTime.now().format(PROJECT_NO_DATE_FMT), projectId);
    }

    @Override
    @Auditable(operationType = "REJECT", targetType = "APPROVAL", targetIdSpEl = "#taskId",
            remark = "审批驳回/退回")
    public void rejectTask(String taskId, String toNode, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("驳回理由不能为空");
        }
        String bizKey = engine.reject(taskId, toNode, comment);
        Project p = (bizKey != null) ? safeSelectProject(bizKey) : null;
        if (p != null) {
            // 回写 REJECTED：项目回到申请人手中可修改重报（2026-10-22 修：此前只发通知
            // 不回写，列表审批状态永远停在「审批中」——虎哥实测流程走完状态不变）。
            // processInstanceId 保留：重新提交时 submit 先 terminate 终结旧实例再起新流程，
            // 避免旧实例残留产生双份待办。
            p.setApprovalStatus("REJECTED");
            mapper.updateById(p);
            log.info("[project] 审批驳回回写 id={} comment={}", p.getId(), comment);
            notifyResult(p.getSalesManagerId(), bizKey, taskId, p.getProjectName(), "已退回修改", currentUsername(), comment);
        } else {
            log.warn("[project] 审批驳回但未回写：bizKey={} 找不到项目", bizKey);
        }
    }

    @Override
    public List<TaskInfo> myTasks(String username) {
        List<TaskInfo> tasks = engine.listMyTasks(username);
        if (tasks.isEmpty()) {
            return tasks;
        }
        // enrich 业务标题 + 可驳回目标节点（2026-10-23 通用化：按 processDefinitionKey 分发，
        // project_initiate → fin_project 项目名；shipment_approval → fin_shipment 批次号·项目名）
        Map<String, String> titleMap = enrichBizTitles(tasks);
        for (TaskInfo t : tasks) {
            t.setBizTitle(titleMap.get(t.getBizKey()));
            t.setRejectTargets(rejectTargetsOf(t.getProcessDefinitionKey(), t.getNodeId()));
        }
        return tasks;
    }

    /**
     * 按流程 key 批量 enrich 业务标题（bizKey 在项目/发货 ID 空间各自独立，必须按 key 分组合查，
     * 不能混查——project 150 与 shipment 150 是两张表的不同单据）。
     *
     * @return bizKey → 标题（project：项目名；shipment：批次号 · 项目名）
     */
    private Map<String, String> enrichBizTitles(List<TaskInfo> tasks) {
        Map<String, String> titleMap = new HashMap<>();
        Set<String> projectKeys = keysOf(tasks, INITIATE_PROCESS_CODE);
        if (!projectKeys.isEmpty()) {
            List<Long> ids = projectKeys.stream().map(Long::parseLong).collect(Collectors.toList());
            List<Project> projects = mapper.selectList(new LambdaQueryWrapper<Project>()
                    .in(Project::getId, ids).select(Project::getId, Project::getProjectName));
            for (Project p : projects) {
                titleMap.put(String.valueOf(p.getId()), p.getProjectName());
            }
        }
        Set<String> shipmentKeys = keysOf(tasks, SHIPMENT_PROCESS_CODE);
        if (!shipmentKeys.isEmpty()) {
            List<Long> ids = shipmentKeys.stream().map(Long::parseLong).collect(Collectors.toList());
            List<Shipment> shipments = shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>()
                    .in(Shipment::getId, ids).select(Shipment::getId, Shipment::getBatchNo, Shipment::getProjectId));
            // 批次号 + 项目名（审批人一眼看到是哪个项目的货）；项目名批量补查
            Set<Long> projectIds = shipments.stream().map(Shipment::getProjectId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            Map<Long, String> projectNames = new HashMap<>();
            if (!projectIds.isEmpty()) {
                List<Project> projects = mapper.selectList(new LambdaQueryWrapper<Project>()
                        .in(Project::getId, projectIds).select(Project::getId, Project::getProjectName));
                for (Project p : projects) {
                    projectNames.put(p.getId(), p.getProjectName());
                }
            }
            for (Shipment s : shipments) {
                String pn = projectNames.get(s.getProjectId());
                String title = s.getBatchNo() == null ? "发货申请" : s.getBatchNo();
                titleMap.put(String.valueOf(s.getId()), pn == null ? title : title + " · " + pn);
            }
        }
        return titleMap;
    }

    /** 取指定流程 key 的任务 bizKey 集合（过滤 null / 非数字） */
    private Set<String> keysOf(List<TaskInfo> tasks, String processCode) {
        return tasks.stream()
                .filter(t -> processCode.equals(t.getProcessDefinitionKey()))
                .map(TaskInfo::getBizKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    @Override
    public IPage<TaskInfo> myTasksPage(String username, Integer pageNum, Integer pageSize,
                                       String bizTitleKw, String taskNameKw) {
        // 全量（三线合并 + enrich）之上过滤 + 内存分页：待办量级小（百级以内），
        // 内存分页零风险；引擎层三线 or 查询的 listPage/count 支持未验证，不引入该路径。
        List<TaskInfo> all = myTasks(username);
        String titleKw = bizTitleKw == null || bizTitleKw.isBlank() ? null : bizTitleKw.trim();
        String nameKw = taskNameKw == null || taskNameKw.isBlank() ? null : taskNameKw.trim();
        List<TaskInfo> filtered = all.stream()
                .filter(t -> titleKw == null || (t.getBizTitle() != null && t.getBizTitle().contains(titleKw)))
                .filter(t -> nameKw == null || (t.getTaskName() != null && t.getTaskName().contains(nameKw)))
                .collect(Collectors.toList());
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        long total = filtered.size();
        int from = (int) Math.min((current - 1) * size, total);
        int to = (int) Math.min(from + size, total);
        IPage<TaskInfo> page = new Page<>(current, size, total);
        page.setRecords(filtered.subList(from, to));
        return page;
    }

    @Override
    public IPage<ApprovalHistoryVO> myApprovedHistoryPage(String username, Integer pageNum, Integer pageSize,
                                                          String bizTitleKw, String taskNameKw) {
        // 同 myTasksPage：引擎全量历史 + enrich 业务标题 → 内存过滤 → 内存分页
        List<ApprovalHistoryVO> all = engine.myApprovedHistory(username);
        for (ApprovalHistoryVO vo : all) {
            vo.setBizTitle(bizTitleOf(vo.getProcessDefinitionKey(), vo.getBizKey()));
        }
        String titleKw = bizTitleKw == null || bizTitleKw.isBlank() ? null : bizTitleKw.trim();
        String nameKw = taskNameKw == null || taskNameKw.isBlank() ? null : taskNameKw.trim();
        List<ApprovalHistoryVO> filtered = all.stream()
                .filter(v -> titleKw == null || (v.getBizTitle() != null && v.getBizTitle().contains(titleKw)))
                .filter(v -> nameKw == null || (v.getTaskName() != null && v.getTaskName().contains(nameKw)))
                .collect(Collectors.toList());
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        long total = filtered.size();
        int from = (int) Math.min((current - 1) * size, total);
        int to = (int) Math.min(from + size, total);
        IPage<ApprovalHistoryVO> page = new Page<>(current, size, total);
        page.setRecords(filtered.subList(from, to));
        return page;
    }

    /**
     * 该流程定义中除当前节点外的全部节点，供前端驳回下拉（2026-10-23 通用化：
     * 按流程 key 查对应定义，不再硬编码立项；发货流程单节点 → 恒空列表）。
     */
    private List<NodeRef> rejectTargetsOf(String processCode, String currentNodeId) {
        if (processCode == null) {
            return Collections.emptyList();
        }
        ProcessDef def = processDefMapper.selectOne(new LambdaQueryWrapper<ProcessDef>()
                .eq(ProcessDef::getProcessCode, processCode));
        if (def == null || def.getNodeList() == null) {
            return Collections.emptyList();
        }
        List<NodeRef> out = new ArrayList<>();
        for (NodeCfg n : def.getNodeList()) {
            if (n.getNodeId() != null && n.getNodeId().equals(currentNodeId)) {
                continue;
            }
            out.add(new NodeRef(n.getNodeId(), n.getName()));
        }
        return out;
    }

    private Project safeSelectProject(String bizKey) {
        try {
            return mapper.selectById(Long.parseLong(bizKey));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 历史记录业务标题（2026-10-23 通用化：按流程 key 分发查表）。
     * project_initiate → 项目名；shipment_approval → 批次号 · 项目名。
     */
    private String bizTitleOf(String processCode, String bizKey) {
        if (bizKey == null) {
            return null;
        }
        if (SHIPMENT_PROCESS_CODE.equals(processCode)) {
            Shipment s = safeSelectShipment(bizKey);
            if (s == null) {
                return null;
            }
            Project p = s.getProjectId() == null ? null : mapper.selectById(s.getProjectId());
            String title = s.getBatchNo() == null ? "发货申请" : s.getBatchNo();
            return p == null ? title : title + " · " + p.getProjectName();
        }
        Project p = safeSelectProject(bizKey);
        return p == null ? null : p.getProjectName();
    }

    private Shipment safeSelectShipment(String bizKey) {
        try {
            return shipmentMapper.selectById(Long.parseLong(bizKey));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ===================== 通知（⑤：接入现有通知中枢） =====================

    /** 给一组待办任务的处理人发「待审批」通知（复用 V30 已种模板 APPROVAL_TODO） */
    private void notifyTodo(List<TaskInfo> tasks, String bizTitle) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        String title = (bizTitle == null || bizTitle.isBlank()) ? "立项审批" : bizTitle;
        for (TaskInfo t : tasks) {
            List<Long> receivers = resolveReceiverIds(t);
            if (receivers.isEmpty()) {
                log.debug("[notify] 任务 {} 无解析到通知接收人，跳过", t.getTaskId());
                continue;
            }
            NotifyCommand cmd = new NotifyCommand();
            cmd.setCode("APPROVAL_TODO");
            cmd.setReceivers(receivers);
            cmd.setBizType("PROJECT");
            cmd.setBizId(t.getBizKey());
            // 待审批通知跳审批中心（2026-10-22 修：此前跳 /project 立项列表，
            // 处理人点通知落到自己无操作权的页面——虎哥实测「跳转不正确」）
            cmd.setLinkUrl("/approval");
            // dedupKey 带 taskId（2026-10-22 修）：默认规则 {type}:{bizType}:{bizId}:{receiverId}
            // 无任务维度——驳回重走同一节点时新任务与旧任务撞键，INSERT IGNORE 静默吃掉，
            // 处理人收不到「新待办」提醒（虎哥实测「通知被干掉」，日志实锤 Updates: 0）。
            // 带 taskId 后：同一任务重复触发才去重，驳回重建的新任务正常再提醒。
            cmd.setDedupKey("APPROVAL_TODO:PROJECT:" + t.getBizKey() + ":" + t.getTaskId());
            cmd.setVars(Map.of("bizTitle", title, "applicant", ""));
            cmd.setOperatorId(currentUserId());
            notificationService.notify(cmd);
        }
    }

    /**
     * 给申请人发「审批结果」通知（复用 V30 已种模板 APPROVAL_RESULT）。
     *
     * <p>dedupKey 必须带 taskId（2026-10-22 虎哥实测「通知被干掉」根因）：默认规则
     * {type}:{bizType}:{bizId}:{receiverId} 粒度太粗——本项目 notifyResult 此前连
     * bizId 都没传（日志实锤 key=APPROVAL_RESULT:PROJECT::12，bizId 空），
     * 同一接收人所有项目的审批结果全撞一个键，INSERT IGNORE 静默吃掉，
     * 只有第一条通知能发出去。带 taskId 后每个审批动作独立成键。</p>
     */
    private void notifyResult(Long applicantId, String bizKey, String taskId, String bizTitle, String result,
                              String approver, String comment) {
        if (applicantId == null) {
            return;
        }
        String title = (bizTitle == null || bizTitle.isBlank()) ? "立项审批" : bizTitle;
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("APPROVAL_RESULT");
        cmd.setReceivers(List.of(applicantId));
        cmd.setBizType("PROJECT");
        cmd.setBizId(bizKey);
        cmd.setDedupKey("APPROVAL_RESULT:PROJECT:" + bizKey + ":" + taskId);
        cmd.setLinkUrl("/project");
        cmd.setVars(Map.of(
                "bizTitle", title,
                "result", result,
                "approver", approver == null ? "" : approver,
                "comment", comment == null ? "" : comment));
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    /** 任务处理人 → userId 列表（assignee / candidateUsers 用户名转 id；candidateGroups 角色全员；会签 UEL 残留则跳过） */
    private List<Long> resolveReceiverIds(TaskInfo t) {
        Set<Long> ids = new LinkedHashSet<>();
        List<String> usernames = new ArrayList<>();
        if (t.getAssignee() != null && !t.getAssignee().isBlank() && !t.getAssignee().contains("${")) {
            usernames.add(t.getAssignee());
        }
        if (t.getCandidateUsers() != null) {
            usernames.addAll(t.getCandidateUsers());
        }
        for (String u : usernames) {
            User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, u).select(User::getId));
            if (user != null) {
                ids.add(user.getId());
            }
        }
        if (t.getCandidateGroups() != null && !t.getCandidateGroups().isEmpty()) {
            // ROLE 候选组：role_code → 角色 → 全体关联用户
            // （2026-10-22 修：此前只解析用户名线，主管角色节点的待办通知全被跳过——虎哥实测无通知）
            List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                    .in(Role::getRoleCode, t.getCandidateGroups()).select(Role::getId));
            if (!roles.isEmpty()) {
                List<Long> roleIds = roles.stream().map(Role::getId).collect(Collectors.toList());
                List<UserRole> userRoles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .in(UserRole::getRoleId, roleIds));
                for (UserRole ur : userRoles) {
                    ids.add(ur.getUserId());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private Long currentUserId() {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, currentUsername()).select(User::getId));
        return u == null ? null : u.getId();
    }

    // ===================== 私有 =====================

    private ProjectVO toVO(Project p, Map<String, DictData> approvalDict,
                           Map<Long, String> userNames) {
        ProjectVO vo = new ProjectVO();
        vo.setId(p.getId());
        vo.setProjectNo(p.getProjectNo());
        vo.setProjectName(p.getProjectName());
        vo.setCustomerName(p.getCustomerName());
        vo.setSalesManagerId(p.getSalesManagerId());
        vo.setSalesManagerName(p.getSalesManagerId() != null
                ? userNames.get(p.getSalesManagerId()) : null);
        vo.setContractStatus(p.getContractStatus());
        vo.setContractAmount(p.getContractAmount());
        // 项目数据六件套之一：累计发货 / 已收 / 退货金额（按项目聚合 fin_shipment，决策#7）
        List<Shipment> shippings = shipmentMapper.selectList(
                new LambdaQueryWrapper<Shipment>()
                        .eq(Shipment::getProjectId, p.getId())
                        .select(Shipment::getShipmentAmount,
                                Shipment::getReceivedAmount,
                                Shipment::getReturnedAmount));
        BigDecimal shipped = BigDecimal.ZERO, received = BigDecimal.ZERO, returned = BigDecimal.ZERO;
        for (Shipment s : shippings) {
            if (s.getShipmentAmount() != null) shipped = shipped.add(s.getShipmentAmount());
            if (s.getReceivedAmount() != null) received = received.add(s.getReceivedAmount());
            if (s.getReturnedAmount() != null) returned = returned.add(s.getReturnedAmount());
        }
        vo.setShippedAmount(shipped);
        vo.setReceivedAmount(received);
        vo.setReturnedAmount(returned);
        vo.setContractDesc(p.getContractDesc());
        vo.setContractSignedAt(p.getContractSignedAt());
        vo.setContractSignedBy(p.getContractSignedBy());
        vo.setContractSignedByName(p.getContractSignedBy() != null
                ? userNames.get(p.getContractSignedBy()) : null);
        vo.setApprovalStatus(p.getApprovalStatus());
        DictData approvalMeta = approvalDict.get(p.getApprovalStatus());
        vo.setApprovalStatusName(approvalMeta != null ? approvalMeta.getDictLabel() : null);
        vo.setApprovalStatusStyle(approvalMeta != null ? approvalMeta.getStyle() : null);
        vo.setProcessInstanceId(p.getProcessInstanceId());
        vo.setCreatedAt(p.getCreatedAt());
        return vo;
    }

    /** 审批状态字典 map（value → DictData，含 label 与 style），一次查询全量复用 */
    private Map<String, DictData> approvalStatusDict() {
        List<DictData> rows = dictDataMapper.selectList(new LambdaQueryWrapper<DictData>()
                .eq(DictData::getTypeCode, DICT_APPROVAL_STATUS)
                .eq(DictData::getStatus, "ENABLED"));
        Map<String, DictData> map = new HashMap<>();
        for (DictData d : rows) {
            map.put(d.getDictValue(), d);
        }
        return map;
    }

    /**
     * 用户 id → 展示名（nickname 优先，空则 username；查不到的 id 不进 map，
     * 由前端兜底显示 id）。批量入参一次查询，空集合直接返回空 map 不发 SQL。
     * 销售经理与签约人（V46）共用：调用方合并 id 集合一次查完，避免 N+1。
     */
    private Map<Long, String> userDisplayNames(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
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

    /** 当前登录用户名（JwtAuthenticationFilter 写入 SecurityContext） */
    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
