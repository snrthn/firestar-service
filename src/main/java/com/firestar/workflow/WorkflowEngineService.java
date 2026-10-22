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
     * @param taskId    任务 ID
     * @param variables 流程变量（可空）
     * @param comment   审批留言（可空；非空时落 Flowable 评论 ACT_HI_COMMENT，
     *                  时间线历史节点直接可读——不做第二套存储）
     * @return 该任务所属流程实例 ID（调用方据其查新任务 / 业务单据，驱动通知与进度）
     */
    String complete(String taskId, Map<String, Object> variables, String comment);

    /**
     * 驳回：把指定任务退回目标节点（源节点由任务自动解析，调用方只需传目标节点 id）。
     *
     * @param comment 驳回理由（落 Flowable 评论，时间线可读）
     * @return 业务单据标识（= 流程实例 businessKey，如项目 ID），便于调用方通知被驳回方
     */
    String reject(String taskId, String toNode, String comment);

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
