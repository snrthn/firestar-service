package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

/**
 * 流程实例待办任务视图（门面返回，避免业务层直接触碰 Flowable {@code Task} 类型）。
 *
 * <p>覆盖「待办双查」语义：assignee 命中个人任务，candidateGroups / candidateUsers 命中
 * 角色组 / 候选人任务（ROLE 节点走 candidateGroups，LEVEL 多实例会签走 assignee 逐人）。
 * 调用方（审批进度视图）据此展示「当前单据卡在谁 / 哪个角色那里」。</p>
 */
@Getter
@Setter
public class TaskInfo {

    /** Flowable 任务 ID（complete / 驳回跳转的入参） */
    private String taskId;

    /** 任务名称（取自节点 name） */
    private String taskName;

    /** 节点 id（= Flowable taskDefinitionKey，驳回时作为 toNode 目标回填） */
    private String nodeId;

    /** 所属流程实例 ID */
    private String processInstanceId;

    /** 业务单据标识（= 流程实例的 businessKey，如项目 ID），审批中心据此关联业务对象 */
    private String bizKey;

    /**
     * 流程定义 key（project_initiate / shipment_approval …，引擎层从 processDefinitionId 解析）。
     * 审批中心 enrich 按此分发：标题查哪张业务表、驳回目标查哪个流程定义（2026-10-23 通用化）。
     */
    private String processDefinitionKey;

    /** 业务标题（如项目名），由业务层 enrich，引擎层不直接查业务表 */
    private String bizTitle;

    /** 可驳回目标节点列表（审批中心驳回下拉用，由业务层 enrich） */
    private List<NodeRef> rejectTargets;

    /** 指派给个人的用户名（USER / LEVEL 会签节点有值；ROLE 候选组节点为 null） */
    private String assignee;

    /** 候选组（ROLE 节点：sys_role.role_code 列表） */
    private List<String> candidateGroups;

    /** 候选人（LEVEL 会签节点走 assignee 逐人，此处一般为空） */
    private List<String> candidateUsers;

    /**
     * 处理人展示名集合（后端解析，前端直接渲染标签无需再查）：
     * assignee / candidateUsers 按 username 解析昵称，candidateGroups 按 role_code 解析角色名；
     * 查不到时回退原值。顺序：assignee → candidateUsers → candidateGroups。
     */
    private List<String> handlerNames;

    /** 任务创建时间 */
    private Date createTime;

    public TaskInfo() {
    }
}
