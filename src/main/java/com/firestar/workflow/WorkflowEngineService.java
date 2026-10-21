package com.firestar.workflow;

import com.firestar.dto.DeployResult;
import com.firestar.dto.NodeCfg;
import com.firestar.dto.ProcessDefVersionVO;
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
     *   <li>LEVEL → userTask.assignee = "${levelResolver.resolve(execution)}"（Step 3 注册 bean）</li>
     * </ul>
     * 驳回跳过网关（skipNodeIds，POC 已验证）留 Step 3 接驳回时补进生成器。
     */
    BpmnModel generateBpmn(String processKey, String processName, List<NodeCfg> nodes);

    /** 部署流程定义，返回部署信息（deploymentId / processDefinitionId / version） */
    DeployResult deploy(String processKey, String processName, BpmnModel model);

    /** 查询某流程 key 的全部历史版本（按版本号降序） */
    List<ProcessDefVersionVO> listVersions(String processKey);

    // ===================== Step 3 占位（本阶段不实现，仅声明签名约束） =====================

    /** 发起流程实例 */
    String start(String processKey, Map<String, Object> variables);

    /** 完成当前任务 */
    void complete(String taskId, Map<String, Object> variables);

    /** 流程实例内节点跳转（驳回/加签用） */
    void moveTo(String processInstanceId, String fromNode, String toNode);

    /** 查询流程实例的待办任务 */
    List<?> listTasks(String processInstanceId);
}
