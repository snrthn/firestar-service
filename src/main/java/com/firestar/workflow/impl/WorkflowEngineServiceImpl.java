package com.firestar.workflow.impl;

import com.firestar.dto.ApprovalHistoryVO;
import com.firestar.dto.DeployResult;
import com.firestar.dto.NodeCfg;
import com.firestar.dto.ProcessDefVersionVO;
import com.firestar.dto.TaskInfo;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.entity.Role;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.workflow.WorkflowEngineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.*;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WorkflowEngineService 默认实现。
 *
 * <p>注入 {@link RepositoryService}（部署 / 查询定义）、{@link RuntimeService}（发起实例 / 节点跳转）
 * 与 {@link TaskService}（待办查询 / 完成任务）。</p>
 *
 * <p>BPMN 生成严禁手拼 XML 字符串 —— 一律走 {@link BpmnModel} API（P3 方案纪律，可避免命名空间/转义类低级 bug）。</p>
 */
@Slf4j
@Service
public class WorkflowEngineServiceImpl implements WorkflowEngineService {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final IdentityService identityService;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;

    /** 部署时间格式化（版本列表展示用） */
    private static final DateTimeFormatter DEPLOY_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    public WorkflowEngineServiceImpl(RepositoryService repositoryService, RuntimeService runtimeService,
                                    TaskService taskService, HistoryService historyService,
                                    IdentityService identityService,
                                    UserMapper userMapper,
                                    UserRoleMapper userRoleMapper, RoleMapper roleMapper) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.identityService = identityService;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public BpmnModel generateBpmn(String processKey, String processName, List<NodeCfg> nodes) {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId(processKey);
        process.setName(processName);
        model.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        EndEvent end = new EndEvent();
        end.setId("end");
        process.addFlowElement(end);

        String prev = "start";
        for (NodeCfg n : nodes) {
            UserTask t = new UserTask();
            t.setId(n.getNodeId());
            t.setName(n.getName());
            String type = n.getType() == null ? "USER" : n.getType();
            switch (type) {
                case "ROLE":
                    // 候选组用 role_code（与 §0-C 决策一致：candidateGroups = sys_role.role_code）
                    t.setCandidateGroups(Collections.singletonList(String.valueOf(n.getAssignee())));
                    break;
                case "LEVEL":
                    // 会签：同职级全员，每人一个并行任务，全部通过才算过（虎哥 2026-10-22 拍板）。
                    // 职级编码内联进 UEL，运行时 LevelResolver 查出该职级全体用户名 → 多实例逐人生成任务。
                    MultiInstanceLoopCharacteristics mi = new MultiInstanceLoopCharacteristics();
                    mi.setInputDataItem("${levelResolver.resolveList('" + n.getAssignee() + "')}");
                    mi.setElementVariable("levelApprover");
                    mi.setSequential(false);
                    t.setAssignee("${levelApprover}");
                    t.setLoopCharacteristics(mi);
                    break;
                case "USER":
                default:
                    // USER 节点 assignee 存的是 sys_user.id；解析成 username 以匹配 SecurityContext principal，
                    // 保证待办查询（按当前登录用户名）能命中该任务。
                    t.setAssignee(resolveAssigneeUsername(n.getAssignee()));
                    break;
            }
            process.addFlowElement(t);
            process.addFlowElement(sf("f_" + prev + "_" + n.getNodeId(), prev, n.getNodeId()));
            prev = n.getNodeId();
        }
        process.addFlowElement(sf("f_" + prev + "_end", prev, "end"));
        return model;
    }

    @Override
    public DeployResult deploy(String processKey, String processName, BpmnModel model) {
        byte[] xml = new BpmnXMLConverter().convertToXML(model);
        Deployment d = repositoryService.createDeployment()
                .addBytes(processKey + ".bpmn20.xml", xml)
                .name(processName)
                .deploy();
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .deploymentId(d.getId())
                .singleResult();
        log.info("[workflow] 部署流程 key={} name={} version={} deploymentId={}",
                processKey, processName, pd.getVersion(), d.getId());
        return new DeployResult(d.getId(), pd.getId(), pd.getVersion());
    }

    @Override
    public List<ProcessDefVersionVO> listVersions(String processKey) {
        List<ProcessDefinition> pds = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey)
                .orderByProcessDefinitionVersion().desc()
                .list();
        List<ProcessDefVersionVO> vos = new ArrayList<>();
        for (ProcessDefinition pd : pds) {
            ProcessDefVersionVO vo = new ProcessDefVersionVO();
            vo.setVersion(pd.getVersion());
            vo.setProcessDefinitionId(pd.getId());
            vo.setDeploymentId(pd.getDeploymentId());
            vo.setName(pd.getName());
            vo.setSuspended(pd.isSuspended());
            // 部署时间：Flowable 的 ProcessDefinition 不直挂时间，需反查 Deployment
            Deployment dep = repositoryService.createDeploymentQuery()
                    .deploymentId(pd.getDeploymentId()).singleResult();
            if (dep != null && dep.getDeploymentTime() != null) {
                vo.setDeploymentTime(DEPLOY_TIME_FMT.format(
                        dep.getDeploymentTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()));
            }
            vos.add(vo);
        }
        return vos;
    }

    private SequenceFlow sf(String id, String src, String tgt) {
        SequenceFlow f = new SequenceFlow(src, tgt);
        f.setId(id);
        return f;
    }

    /** USER 节点 assignee 存的是 sys_user.id，解析成 username（兜底：非数字则原样返回） */
    private String resolveAssigneeUsername(String assignee) {
        if (assignee == null || assignee.isBlank()) {
            return assignee;
        }
        try {
            Long id = Long.parseLong(assignee.trim());
            User u = userMapper.selectById(id);
            if (u != null && u.getUsername() != null && !u.getUsername().isBlank()) {
                return u.getUsername();
            }
        } catch (NumberFormatException ignored) {
            // 非数字 assignee（如直接配了用户名）原样返回
        }
        return assignee;
    }

    // ===================== Step 3 起：运行时能力 =====================

    @Override
    public String start(String processKey, String businessKey, Map<String, Object> variables) {
        // 前置校验：key 必须已有部署版本，否则 Flowable 抛 "no processes deployed with key='...'"
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey).latestVersion().singleResult();
        if (pd == null) {
            throw new IllegalArgumentException("流程未部署，无法发起：" + processKey);
        }
        Map<String, Object> vars = variables == null ? new HashMap<>() : new HashMap<>(variables);
        // POC 配方（PocRejectSkipTest 实锤）：skipNodeIds 必须初始化为空集合——
        // 驳回跳过网关的条件表达式 ${!skipNodeIds.contains('node_2')} 对 null 集合求值会抛
        // FlowableUelException，实例根本起不来。缺失即补空集合，调用方无感。
        if (!vars.containsKey("skipNodeIds") || vars.get("skipNodeIds") == null) {
            vars.put("skipNodeIds", new ArrayList<String>());
        }
        // 落 Flowable 原生发起人（ACT_HI_PROCINST.START_USER_ID_）：不 set authenticatedUserId
        // 该字段恒为 null（此前从未 set，「我发起的」少了一条引擎侧来源，2026-10-22）
        setAuthenticatedUser();
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(processKey, businessKey, vars);
        log.info("[workflow] 发起流程 key={} businessKey={} instanceId={} definitionVersion={}",
                processKey, businessKey, pi.getId(), pi.getProcessDefinitionVersion());
        return pi.getId();
    }

    @Override
    public String complete(String taskId, Map<String, Object> variables, String comment) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new IllegalArgumentException(describeMissingTask(taskId));
        }
        // 候选人校验（2026-10-23）：非候选人/非管理员直接拒，堵「谁拿 taskId 都能批」
        assertHandleable(task);
        String instanceId = task.getProcessInstanceId();
        // claim 落实际操作人（2026-10-22 虎哥截图实锤：时间线历史节点「处理人：—」）：
        // ROLE/USER 候选组任务不 claim 直接 complete 时 ACT_HI_TASKINST.ASSIGNEE_ 为 null，
        // 时间线拿不到实际操作人。complete 前先认领，历史 assignee 即落当前用户；
        // 会签子任务（LEVEL 多实例）assignee 本就是本人，claim 覆盖无副作用。
        claimAsCurrentUser(task);
        // 落评论归属（ACT_HI_COMMENT.USER_ID_）：addComment 取 IdentityService 的
        // authenticatedUserId，不 set 则评论 userId 恒 null——listTimeline 的
        // 历史节点兜底链（assignee → 评论 userId）第二级会失效
        setAuthenticatedUser();
        // 审批留言落 Flowable 评论（ACT_HI_COMMENT）：必须在 complete 之前 addComment——
        // 任务完成后评论虽仍在，但按「操作时留痕」语义先写后办；时间线按 taskId 读回。
        if (comment != null && !comment.isBlank()) {
            taskService.addComment(taskId, instanceId, comment.trim());
        }
        taskService.complete(taskId, variables == null ? Collections.emptyMap() : variables);
        log.info("[workflow] 完成任务 taskId={} instanceId={} comment={}", taskId, instanceId, comment);
        return instanceId;
    }

    @Override
    public String reject(String taskId, String toNode, String comment) {
        if (taskId == null || taskId.isBlank() || toNode == null || toNode.isBlank()) {
            throw new IllegalArgumentException("taskId / toNode 均不能为空");
        }
        // 源节点从任务自身解析（taskDefinitionKey = 节点 id），调用方无需背内部节点 id
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new IllegalArgumentException(describeMissingTask(taskId));
        }
        // 同 complete：先过候选人校验再动流程
        assertHandleable(task);
        String instanceId = task.getProcessInstanceId();
        // 同 complete：claim 落实际操作人 + 落评论归属 userId（2026-10-22 虎哥截图实锤）
        claimAsCurrentUser(task);
        setAuthenticatedUser();
        // 驳回理由同样落评论：moveActivityIdTo 会删除源任务，评论按 taskId 保留在历史表，
        // 时间线上该节点显示 REJECTED + 留言（2026-10-22 虎哥：历史含留言）
        if (comment != null && !comment.isBlank()) {
            taskService.addComment(taskId, instanceId, comment.trim());
        }
        // processInstanceId 必须显式传给 builder：ChangeActivityStateCmd 无实例 id 直接抛
        // FlowableIllegalArgumentException「Process instance id is required」（2026-10-22
        // 虎哥实测驳回 500，app.log 堆栈实锤 ChangeActivityStateCmd.execute:42）
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(instanceId)
                .moveActivityIdTo(task.getTaskDefinitionKey(), toNode)
                .changeState();
        log.info("[workflow] 驳回任务 taskId={} instanceId={} -> {} comment={}", taskId, instanceId, toNode, comment);
        return getBusinessKey(instanceId);
    }

    /**
     * 任务已不在运行时表时的上下文化报错（2026-10-22 虎哥实测：审批点通过报 500）。
     * 典型场景：项目重新提交 → submit 先 terminate 终结旧实例（历史表 DELETE_REASON_=
     * 「项目重新提交审批，终结旧实例 id=xx」）→ 前端待办列表还是旧的，持旧 taskId 点审批。
     * 从历史表读回删除原因拼进 message，用户才知道该刷新列表，而不是干一句「不存在」。
     */
    private String describeMissingTask(String taskId) {
        HistoricTaskInstance h = historyService.createHistoricTaskInstanceQuery()
                .taskId(taskId).singleResult();
        if (h != null && h.getDeleteReason() != null && !h.getDeleteReason().isBlank()) {
            return "任务已结束（" + h.getDeleteReason() + "），请刷新待办列表后重试";
        }
        return "任务不存在或已结束，请刷新待办列表后重试";
    }

    @Override
    public String businessKeyOfTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        return task == null ? null : getBusinessKey(task.getProcessInstanceId());
    }

    @Override
    public String getBusinessKey(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return null;
        }
        // 先查运行时实例（流程进行中）；查不到再查历史实例表——
        // 审批通过后流程走到结束事件，runtime 实例被同步清理，只查 runtime
        // 会返回 null，导致「流程走完但业务状态不回写」（2026-10-22 虎哥实测）
        var pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult();
        if (pi != null) {
            return pi.getBusinessKey();
        }
        var hi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult();
        return hi != null ? hi.getBusinessKey() : null;
    }

    @Override
    public void moveTo(String processInstanceId, String fromNode, String toNode) {
        if (processInstanceId == null || processInstanceId.isBlank()
                || fromNode == null || fromNode.isBlank()
                || toNode == null || toNode.isBlank()) {
            throw new IllegalArgumentException("processInstanceId / fromNode / toNode 均不能为空");
        }
        // 驳回/加签：把当前停在 fromNode 的执行（含其 userTask）整体迁移到 toNode。
        // Flowable 7.x ChangeActivityStateBuilder.moveActivityIdTo 会终结源任务并在目标节点新建任务。
        // 同 reject：builder 必须带 processInstanceId（ChangeActivityStateCmd 硬校验，
        // 缺了抛 FlowableIllegalArgumentException——2026-10-22 驳回 500 同根因）
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstanceId)
                .moveActivityIdTo(fromNode, toNode)
                .changeState();
        log.info("[workflow] 节点跳转（驳回/加签） instanceId={} {} -> {}", processInstanceId, fromNode, toNode);
    }

    @Override
    public void terminate(String processInstanceId, String reason) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return;
        }
        // 实例已自然结束（审批通过后走完）时 deleteProcessInstance 会抛，
        // 属预期场景（重新提交只可能发生在 REJECTED，但防御性处理不报错）
        try {
            runtimeService.deleteProcessInstance(processInstanceId, reason == null ? "terminate" : reason);
            log.info("[workflow] 终结流程实例 instanceId={} reason={}", processInstanceId, reason);
        } catch (Exception e) {
            log.warn("[workflow] 终结流程实例失败（可能已结束）instanceId={} err={}",
                    processInstanceId, e.getMessage());
        }
    }

    @Override
    public List<Long> withdraw(String processInstanceId, String username, String reason) {
        if (processInstanceId == null || processInstanceId.isBlank()
                || username == null || username.isBlank()) {
            throw new IllegalArgumentException("流程实例或操作人缺失，无法撤回");
        }
        // 闸①发起人身份：运行中实例在历史表同样有记录，直接查历史表最稳
        HistoricProcessInstance hi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (hi == null) {
            throw new IllegalArgumentException("流程实例不存在，无法撤回");
        }
        if (!username.equals(hi.getStartUserId())) {
            throw new IllegalArgumentException("仅发起人可撤回本申请");
        }
        // 闸②流程仍在运行：已自然结束（审完）的实例撤回语义不存在
        if (runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult() == null) {
            throw new IllegalArgumentException("流程已结束，无法撤回");
        }
        // 先收集已审批人再终结：terminate 后历史任务虽仍在，但先取可避免任何时序歧义
        Set<String> approved = new LinkedHashSet<>();
        for (HistoricTaskInstance h : historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).finished().list()) {
            String a = h.getAssignee();
            if (a != null && !a.isBlank() && !a.equals(username)) {
                approved.add(a);
            }
        }
        // 闸③并发：terminate 与审批撞车时先到先赢，后到者由 complete 侧报错（候选人校验兜底）
        terminate(processInstanceId, reason);
        List<Long> ids = new ArrayList<>();
        for (String u : approved) {
            User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, u));
            if (user != null && user.getId() != null) {
                ids.add(user.getId());
            }
        }
        log.info("[workflow] 申请人撤回 instanceId={} user={} 已审批人={}", processInstanceId, username, approved);
        return ids;
    }

    @Override
    public List<TaskInfo> listTasks(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return Collections.emptyList();
        }
        // 待办双查：运行时任务表只存 active 任务，无论 assignee（USER / LEVEL 会签）还是
        // candidateGroups（ROLE）都会落在同一实例的 active 任务里，一次 query 全量返回。
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .orderByTaskCreateTime().asc()
                .list();
        List<TaskInfo> result = new ArrayList<>();
        for (Task t : tasks) {
            result.add(toTaskInfo(t));
        }
        return result;
    }

    @Override
    public List<TaskInfo> listMyTasks(String username) {
        if (username == null || username.isBlank()) {
            return Collections.emptyList();
        }
        // 待办三查（2026-10-22 修，虎哥实测：主管账号提交后待办为空、无通知）：
        //   线 1 taskCandidateOrAssigned(username)：assignee / candidateUser（USER / LEVEL 会签节点）；
        //   线 2 taskCandidateGroup(roleCode)：ROLE 候选组节点（BPMN 里 candidateGroups = sys_role.role_code）。
        // 项目从未往 Flowable group 体系（ACT_ID_MEMBERSHIP）写过用户-角色关系，线 1 永远查不到
        // candidateGroups 任务——必须按当前用户名所属角色 code 逐组查（roleCodesOf）。
        Map<String, Task> merged = new LinkedHashMap<>();
        for (Task t : taskService.createTaskQuery()
                .taskCandidateOrAssigned(username).list()) {
            merged.put(t.getId(), t);
        }
        for (String roleCode : roleCodesOf(username)) {
            for (Task t : taskService.createTaskQuery()
                    .taskCandidateGroup(roleCode).list()) {
                merged.put(t.getId(), t);
            }
        }
        List<Task> tasks = new ArrayList<>(merged.values());
        // 按创建时间降序（null 垫底），与线 1 原查询的 orderByTaskCreateTime().desc() 语义一致
        tasks.sort(Comparator.comparing(Task::getCreateTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<TaskInfo> result = new ArrayList<>();
        for (Task t : tasks) {
            result.add(toTaskInfo(t));
        }
        return result;
    }

    @Override
    public List<TimelineNodeVO> listTimeline(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return Collections.emptyList();
        }
        List<HistoricTaskInstance> his = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricTaskInstanceStartTime().asc()
                .list();
        return buildTimelineFromTasks(his);
    }

    /**
     * 按业务单据标识（businessKey，如项目 ID）聚合该单据的全部历史审批时间线。
     * <p>重提会 terminate 旧流程实例并 start 新实例，二者 businessKey 同为项目 ID
     * （{@code submit} 时 businessKey = String.valueOf(projectId)），故可 union 出
     * 重提前后的完整时间线——2026-10-23 虎哥：驳回/重提后历史必须完整，不可只从最后一次实例开始。</p>
     */
    @Override
    public List<TimelineNodeVO> listTimelineByBusinessKey(String businessKey) {
        if (businessKey == null || businessKey.isBlank()) {
            return Collections.emptyList();
        }
        // 聚合该 businessKey 下的所有流程实例（含已 terminate 的旧实例，其 ACT_HI_PROCINST 仍在）
        List<HistoricProcessInstance> procInsts = historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey)
                .list();
        List<HistoricTaskInstance> allTasks = new ArrayList<>();
        for (HistoricProcessInstance pi : procInsts) {
            allTasks.addAll(historyService.createHistoricTaskInstanceQuery()
                    .processInstanceId(pi.getId())
                    .list());
        }
        if (allTasks.isEmpty()) {
            return Collections.emptyList();
        }
        // 跨实例统一按开始时间升序（单实例查询已隐含此序，跨实例必须显式排）
        allTasks.sort(Comparator.comparing(HistoricTaskInstance::getStartTime,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return buildTimelineFromTasks(allTasks);
    }

    /**
     * 由历史任务列表构建时间线节点。ACT_HI_TASKINST 单源：已完成 / 已驳回删除
     * （deleteReason 非空）/ 进行中（endTime = null）三类同表。被 skipNodeIds 条件流
     * 绕过的节点不会创建任务实例，天然不出现在时间线里。调用方需保证入参按开始时间升序。
     */
    private List<TimelineNodeVO> buildTimelineFromTasks(List<HistoricTaskInstance> his) {
        List<TimelineNodeVO> result = new ArrayList<>();
        for (HistoricTaskInstance h : his) {
            TimelineNodeVO vo = new TimelineNodeVO();
            vo.setNodeId(h.getTaskDefinitionKey());
            vo.setNodeName(h.getName());
            vo.setStartTime(toLocalDateTime(h.getStartTime()));
            vo.setEndTime(toLocalDateTime(h.getEndTime()));
            vo.setResult(h.getEndTime() == null ? "ACTIVE"
                    : (h.getDeleteReason() != null && !h.getDeleteReason().isEmpty() ? "REJECTED" : "DONE"));
            // 审批留言：ACT_HI_COMMENT 按 taskId 读回；一次审批一条，多条取最后
            // （HistoricTaskInstance.getId() 即 taskId，历史任务实例 id = 原任务 id）。
            // 先读评论再定处理人——历史节点的处理人兜底链要用到评论的 userId
            List<Comment> comments = taskService.getTaskComments(h.getId());
            if ("ACTIVE".equals(vo.getResult())) {
                // 进行中：候选集合（assignee 已认领即实际操作人；否则候选人/角色组）
                List<String> groups = new ArrayList<>();
                List<String> users = new ArrayList<>();
                for (IdentityLink link : taskService.getIdentityLinksForTask(h.getId())) {
                    if (IdentityLinkType.CANDIDATE.equals(link.getType())) {
                        if (link.getGroupId() != null) groups.add(link.getGroupId());
                        if (link.getUserId() != null) users.add(link.getUserId());
                    }
                }
                vo.setHandlerNames(resolveHandlerNames(h.getAssignee(), users, groups));
            } else {
                // 历史节点：只展示实际操作人，三级兜底（2026-10-22 虎哥截图实锤第一级全空）：
                //   ① assignee——claim 修复后的新数据必有值；
                //   ② 评论 userId——claim 修复前的老数据（ROLE/USER 候选组直接 complete），
                //      addComment 落的当前认证用户（本次同步补 setAuthenticatedUser，新评论必有）；
                //   ③ 都空则空集合，前端显示「—」（比错显示候选人集合语义正确）。
                String operator = h.getAssignee();
                if ((operator == null || operator.isBlank())
                        && comments != null && !comments.isEmpty()) {
                    for (int i = comments.size() - 1; i >= 0; i--) {
                        String userId = comments.get(i).getUserId();
                        if (userId != null && !userId.isBlank()) {
                            operator = userId;
                            break;
                        }
                    }
                }
                vo.setHandlerNames(resolveHandlerNames(operator,
                        Collections.emptyList(), Collections.emptyList()));
            }
            if (comments != null && !comments.isEmpty()) {
                vo.setComment(comments.get(comments.size() - 1).getFullMessage());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public List<ApprovalHistoryVO> myApprovedHistory(String username) {
        if (username == null || username.isBlank()) {
            return Collections.emptyList();
        }
        // 查询实际处理人：Flowable 7.1.0 的 taskCompletedBy 走 ACT_HI_TASKINST.COMPLETED_BY_，
        // 而 complete 链路实测不写该列（评论 USER_ID_/procinst START_USER_ID_ 都写了、
        // 唯独它全 NULL，2026-10-22 DB 实锤）——taskCompletedBy 恒空，禁用。
        // 改查 ASSIGNEE_：claim 修复（complete 前 claimAsCurrentUser）后新数据必有值；
        // 旧数据由 V48 迁移用评论 userId 回填 ASSIGNEE_。+ finished 排除仍挂着的任务。按结束时间倒序。
        List<HistoricTaskInstance> his = historyService.createHistoricTaskInstanceQuery()
                .taskAssignee(username)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .list();
        List<ApprovalHistoryVO> result = new ArrayList<>();
        for (HistoricTaskInstance h : his) {
            ApprovalHistoryVO vo = new ApprovalHistoryVO();
            vo.setTaskId(h.getId());
            vo.setTaskName(h.getName());
            vo.setNodeId(h.getTaskDefinitionKey());
            vo.setProcessInstanceId(h.getProcessInstanceId());
            vo.setProcessDefinitionKey(defKeyOf(h.getProcessDefinitionId()));
            // bizKey 必填（2026-10-23 虎哥：「我已审批」业务列整列空白）：审批中心门面
            // （ProjectServiceImpl.myApprovedHistoryPage）靠它 enrich 业务标题，此前漏填
            // 导致 bizKey/bizTitle 恒 null。getBusinessKey 内部 runtime 查不到会兜底查
            // 历史实例表——本场景流程大多已结束，正好适配。
            vo.setBizKey(getBusinessKey(h.getProcessInstanceId()));
            vo.setEndTime(toLocalDateTime(h.getEndTime()));
            // 结果列判据同时间线：deleteReason 非空 = 该任务被 moveActivityIdTo 删除 = 我在此节点驳回
            vo.setResult(h.getDeleteReason() != null && !h.getDeleteReason().isEmpty()
                    ? "REJECTED" : "DONE");
            // 审批留言 + 处理人兜底链同 listTimeline（assignee → 评论 userId）
            List<Comment> comments = taskService.getTaskComments(h.getId());
            if (comments != null && !comments.isEmpty()) {
                vo.setComment(comments.get(comments.size() - 1).getFullMessage());
            }
            String operator = h.getAssignee();
            if ((operator == null || operator.isBlank()) && comments != null && !comments.isEmpty()) {
                for (int i = comments.size() - 1; i >= 0; i--) {
                    String userId = comments.get(i).getUserId();
                    if (userId != null && !userId.isBlank()) {
                        operator = userId;
                        break;
                    }
                }
            }
            vo.setHandlerNames(resolveHandlerNames(operator,
                    Collections.emptyList(), Collections.emptyList()));
            result.add(vo);
        }
        return result;
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    /** Task → TaskInfo（listTasks / listMyTasks 共用；fillCandidates 内含处理人展示名解析） */
    private TaskInfo toTaskInfo(Task t) {
        TaskInfo info = new TaskInfo();
        info.setTaskId(t.getId());
        info.setTaskName(t.getName());
        info.setNodeId(t.getTaskDefinitionKey());
        info.setProcessInstanceId(t.getProcessInstanceId());
        info.setProcessDefinitionKey(defKeyOf(t.getProcessDefinitionId()));
        info.setBizKey(getBusinessKey(t.getProcessInstanceId()));
        info.setAssignee(t.getAssignee());
        fillCandidates(t, info);
        info.setCreateTime(t.getCreateTime());
        return info;
    }

    /** 从任务的 identityLink 抽取候选组 / 候选人，并解析处理人展示名集合 */
    private void fillCandidates(Task t, TaskInfo info) {
        List<String> groups = new ArrayList<>();
        List<String> users = new ArrayList<>();
        for (IdentityLink link : taskService.getIdentityLinksForTask(t.getId())) {
            if (IdentityLinkType.CANDIDATE.equals(link.getType())) {
                if (link.getGroupId() != null) groups.add(link.getGroupId());
                if (link.getUserId() != null) users.add(link.getUserId());
            }
        }
        info.setCandidateGroups(groups);
        info.setCandidateUsers(users);
        info.setHandlerNames(resolveHandlerNames(info.getAssignee(), users, groups));
    }

    /** 当前登录用户名（无认证上下文返回 null） */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    @Override
    public void assertTaskHandleable(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            return; // 任务不存在由调用方（complete/reject）抛，此处不重复
        }
        assertHandleable(task);
    }

    /**
     * 候选人校验（complete/reject 内部 + assertTaskHandleable 共用）：
     * 当前用户命中 assignee / candidateUser / candidateGroups（按用户角色匹配）放行；
     * ROLE_ADMIN 兜底放行（管理员演示/运维需要）；否则抛 IllegalArgumentException。
     */
    private void assertHandleable(Task task) {
        String username = currentUsername();
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("无登录上下文，无法处理审批任务");
        }
        List<String> myRoles = roleCodesOf(username);
        if (myRoles.contains("ROLE_ADMIN")) {
            return;
        }
        if (username.equals(task.getAssignee())) {
            return;
        }
        for (IdentityLink link : taskService.getIdentityLinksForTask(task.getId())) {
            if (!IdentityLinkType.CANDIDATE.equals(link.getType())) {
                continue;
            }
            if (username.equals(link.getUserId())) {
                return;
            }
            if (link.getGroupId() != null && myRoles.contains(link.getGroupId())) {
                return;
            }
        }
        throw new IllegalArgumentException("无权处理该审批任务：当前用户不在候选人范围（任务：" + task.getName() + "）");
    }

    /**
     * Flowable processDefinitionId 形如 {@code key:version:id}（如 shipment_approval:1:4445…），
     * 取冒号前第一段即流程定义 key；无冒号时原样返回。供审批中心 enrich 按流程分发（2026-10-23）。
     */
    private String defKeyOf(String processDefinitionId) {
        if (processDefinitionId == null || processDefinitionId.isEmpty()) {
            return null;
        }
        int idx = processDefinitionId.indexOf(':');
        return idx > 0 ? processDefinitionId.substring(0, idx) : processDefinitionId;
    }

    /**
     * 把当前登录用户设进 Flowable IdentityService（引擎原生审计字段
     * ACT_HI_PROCINST.START_USER_ID_ / ACT_HI_COMMENT.USER_ID_ 的唯一来源）。
     * 不设则这些字段恒为 null（2026-10-22 虎哥实测：时间线处理人空、我发起的缺来源）。
     * 请求级语义：每个请求都会重新 set，不显式复位（异步执行器不读该字段，无污染面）。
     */
    private void setAuthenticatedUser() {
        String username = currentUsername();
        if (username != null && !username.isBlank()) {
            identityService.setAuthenticatedUserId(username);
        }
    }

    /**
     * claim 任务给当前登录用户（complete / reject 前调）。
     * 候选组 / 候选人任务不 claim 直接 complete 时历史表 ASSIGNEE_ 为 null——
     * 时间线「处理人」显示空（2026-10-22 虎哥截图实锤）。无认证上下文时跳过。
     */
    private void claimAsCurrentUser(Task task) {
        String username = currentUsername();
        if (username != null && !username.isBlank()) {
            taskService.claim(task.getId(), username);
        }
    }

    /**
     * 处理人展示名解析（2026-10-22 虎哥要求：处理人解析成中文名、集合前端标签拼接）：
     * assignee / candidateUsers 是 username → 查 sys_user.nickname；
     * candidateGroups 是 role_code → 查 sys_role.role_name；查不到回退原值。
     * 顺序：assignee → candidateUsers → candidateGroups（各自去重）。
     * UEL 残留（${...}，运行时理论上已求值，此处保险）跳过。
     */
    private List<String> resolveHandlerNames(String assignee, List<String> usernames, List<String> roleCodes) {
        Set<String> usernameSet = new LinkedHashSet<>();
        if (assignee != null && !assignee.isBlank() && !assignee.contains("${")) {
            usernameSet.add(assignee);
        }
        if (usernames != null) {
            for (String u : usernames) {
                if (u != null && !u.isBlank() && !u.contains("${")) usernameSet.add(u);
            }
        }
        Set<String> codeSet = new LinkedHashSet<>();
        if (roleCodes != null) {
            for (String c : roleCodes) {
                if (c != null && !c.isBlank()) codeSet.add(c);
            }
        }
        List<String> names = new ArrayList<>();
        if (!usernameSet.isEmpty()) {
            List<User> rows = userMapper.selectList(new LambdaQueryWrapper<User>()
                    .in(User::getUsername, usernameSet)
                    .select(User::getUsername, User::getNickname));
            Map<String, String> nicknameOf = new HashMap<>();
            for (User u : rows) {
                nicknameOf.put(u.getUsername(), u.getNickname());
            }
            for (String u : usernameSet) {
                names.add(nicknameOf.getOrDefault(u, u));
            }
        }
        if (!codeSet.isEmpty()) {
            List<Role> rows = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                    .in(Role::getRoleCode, codeSet)
                    .select(Role::getRoleCode, Role::getRoleName));
            Map<String, String> roleNameOf = new HashMap<>();
            for (Role r : rows) {
                roleNameOf.put(r.getRoleCode(), r.getRoleName());
            }
            for (String c : codeSet) {
                names.add(roleNameOf.getOrDefault(c, c));
            }
        }
        return names;
    }

    /** 用户名 → 所属角色 code 列表（ROLE 候选组待办查询用：sys_user → sys_user_role → sys_role） */
    private List<String> roleCodesOf(String username) {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).select(User::getId));
        if (u == null) {
            return Collections.emptyList();
        }
        List<UserRole> userRoles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, u.getId()));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).collect(Collectors.toList());
        return roleMapper.selectBatchIds(roleIds).stream()
                .map(Role::getRoleCode).collect(Collectors.toList());
    }
}
