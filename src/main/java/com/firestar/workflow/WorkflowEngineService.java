package com.firestar.workflow;

import com.firestar.dto.DeployResult;
import com.firestar.dto.ApprovalHistoryVO;
import com.firestar.dto.NodeCfg;
import com.firestar.dto.ProcessDefVersionVO;
import com.firestar.dto.TaskInfo;
import com.firestar.dto.TimelineNodeVO;
import org.flowable.bpmn.model.BpmnModel;

import java.util.List;
import java.util.Map;

/**
 * Flowable 引擎门面 —— 业务层唯一允许触碰 Flowable API 的出口（架构纪律，详见 P3 方案 §1-D）。
 *
 * <p>Controller / 业务 Service 一律不得直接注入 {@code RepositoryService / RuntimeService / TaskService} 等；
 * 所有与 Flowable 的交互都必须经由此门面，便于统一治理（版本策略、驳回跳过语义、可观测性）。</p>
 */
public interface WorkflowEngineService {

    /**
     * 用节点配置动态生成 BPMN 模型（线性 userTask 链）。
     * <p>Step 2 范围映射：
     * <ul>
     *   <li>USER  → userTask.assignee = assignee（sys_user.id 字符串）</li>
     *   <li>ROLE  → userTask.candidateGroups = [assignee]（sys_role.role_code）</li>
     *   <li>LEVEL → 多实例会签：集合由 ${levelResolver.resolveList('L3')} 提供该职级全员用户名，userTask.assignee = "${levelApprover}"（Step 3 注册 bean）</li>
     * </ul>
     * 驳回跳过网关（skipNodeIds，POC 已验证）留 Step 3 接驳回时补进生成器。
     */
    BpmnModel generateBpmn(String processKey, String processName, List<NodeCfg> nodes);

    /** 部署流程定义，返回部署信息（deploymentId / processDefinitionId / version） */
    DeployResult deploy(String processKey, String processName, BpmnModel model);

    /** 查询某流程 key 的全部历史版本（按版本号降序） */
    List<ProcessDefVersionVO> listVersions(String processKey);

    // ===================== Step 3 起：运行时能力 =====================

    /**
     * 发起流程实例。
     *
     * <p>调用方（业务 Service）负责：查 ENABLED 的流程定义、拼业务变量。
     * 本方法负责：skipNodeIds 防御性初始化（POC 配方，见实现注释）+ 发起 + 返回实例 ID。</p>
     *
     * @param processKey  流程定义 key（sys_process_def.process_code，必须已部署）
     * @param businessKey 业务单据标识（如项目 ID），落 act_ru_execution.BUSINESS_KEY_
     * @param variables   流程变量（调用方至少应传 applicantUsername；skipNodeIds 无需传，自动初始化）
     * @return 流程实例 ID（业务侧回写单据的 process_instance_id）
     */
    String start(String processKey, String businessKey, Map<String, Object> variables);

    /**
     * 完成当前任务。
     *
     * <p>内部强制候选人校验（assertTaskHandleable）：当前用户须为 assignee / candidateUser /
     * candidateGroups 命中其角色之一，ROLE_ADMIN 兜底放行；否则抛 IllegalArgumentException。</p>
     *
     * @param taskId    任务 ID
     * @param variables 流程变量（可空）
     * @param comment   审批留言（可空；非空时落 Flowable 评论 ACT_HI_COMMENT，
     *                  时间线历史节点直接可读——不做第二套存储）
     * @return 该任务所属流程实例 ID（调用方据其查新任务 / 业务单据，驱动通知与进度）
     */
    String complete(String taskId, Map<String, Object> variables, String comment);

    /**
     * 候选人校验（2026-10-23 虎哥：审批入口收归审批中心，引擎层堵「谁拿 taskId 都能批」）：
     * 当前登录用户须命中任务 assignee / candidateUser / candidateGroups（按用户角色匹配），
     * ROLE_ADMIN 兜底放行（管理员演示/运维需要）。任务不存在时静默返回（由调用方抛任务不存在）。
     * reject 内部同样执行；terminate 路径（业务层自行调用）需显式调用本方法。
     */
    void assertTaskHandleable(String taskId);

    /**
     * 驳回：把指定任务退回目标节点（源节点由任务自动解析，调用方只需传目标节点 id）。
     *
     * @param comment 驳回理由（落 Flowable 评论，时间线可读）
     * @return 业务单据标识（= 流程实例 businessKey，如项目 ID），便于调用方通知被驳回方
     */
    String reject(String taskId, String toNode, String comment);

    /**
     * 申请人撤回（2026-10-23 虎哥拍板方案 B）：流程未结束即可撤回，不必等审批人处理。
     *
     * <p>三道闸：① 发起人身份 —— {@code ACT_HI_PROCINST.START_USER_ID_} 必须等于当前用户，
     * 管理员不代撤（撤回是申请人对自有单据的处置权，放大给别人等于授予销毁他人单据的能力）；
     * ② 流程仍在运行 —— 已自然结束（审完）的撤回语义不存在；③ 并发 —— 撤回与审批撞车时
     * 谁先谁赢，后到者报错（terminate 后审批人 complete 查不到任务，由候选人校验兜底）。</p>
     *
     * <p>撤回 = 终结实例 + 留痕，绝不物理删除（财务系统审计要求）。</p>
     *
     * @return 已审批人 userId 列表（不含发起人自己），供业务层发「申请已撤回」通知；
     *         尚无人审批时返回空列表，业务层据此跳过通知（方案 B 强制通知已审批人的落点）
     */
    List<Long> withdraw(String processInstanceId, String username, String reason);

    /** 流程实例内节点跳转（驳回/加签底层原语，需显式传 from/to 节点 id） */
    void moveTo(String processInstanceId, String fromNode, String toNode);

    /**
     * 终结流程实例（删除）：驳回后重新提交时调用，防止旧实例继续产生待办/通知。
     * 实例不存在（已自然结束）时静默返回，不报错。
     */
    void terminate(String processInstanceId, String reason);

    /**
     * 按任务 ID 取其流程实例的 businessKey（任务仍存在时查 runtime，必中）。
     * 供「完成前就要拿到业务键」的时序敏感场景使用——complete 之后流程可能已结束，
     * runtime 实例被清理，只能靠历史表兜底（见 getBusinessKey）。
     */
    String businessKeyOfTask(String taskId);

    /** 查询流程实例的待办任务（覆盖待办双查：assignee 与 candidateGroups 都返回） */
    List<TaskInfo> listTasks(String processInstanceId);

    /**
     * 查询流程实例的完整审批时间线（ACT_HI_TASKINST 单源：已完成 + 已驳回 + 进行中）。
     * 进行中节点（endTime = null）由前端高亮；历史节点只带实际操作人（assignee）。
     */
    List<TimelineNodeVO> listTimeline(String processInstanceId);

    /**
     * 按业务单据标识（businessKey，如项目 ID）聚合该单据的全部历史审批时间线。
     * <p>与 {@link #listTimeline(String)} 的区别：重提会终结旧流程实例并起新实例，
     * 二者 businessKey 相同（submit 时 businessKey = 项目 ID），此方法 union 出重提前后的
     * 完整时间线——2026-10-23 虎哥：驳回/重提后历史必须完整，不可只从最后一次实例开始。</p>
     */
    List<TimelineNodeVO> listTimelineByBusinessKey(String businessKey);

    /** 取流程实例的 businessKey（业务单据标识，如项目 ID）；实例不存在返回 null */
    String getBusinessKey(String instanceId);

    /**
     * 查询指定登录用户的所有 active 待办任务（跨流程实例）。
     * 用于「审批中心 / 我的待办」聚合视图；命中规则：assignee = 用户名 或 candidateUser = 用户名。
     */
    List<TaskInfo> listMyTasks(String username);

    /**
     * 查询指定登录用户审批过的历史任务（跨流程实例，按处理时间倒序）。
     * 2026-10-22 虎哥：用户看不到自己审批过的记录 → 审批中心「我审批的」历史 Tab 数据源。
     * 命中规则：Flowable taskCompletedBy（assignee / owner / 候选人或候选组成员完成均算）；
     * 结果列判据同时间线（deleteReason 非空 = 该节点被驳回跳走）。
     */
    List<ApprovalHistoryVO> myApprovedHistory(String username);
}
