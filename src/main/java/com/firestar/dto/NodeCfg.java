package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 流程节点配置契约 —— 对应 {@code sys_process_def.node_list} JSON 数组的单个元素。
 *
 * <p>生成 BPMN 时的映射（Step 2 范围，驳回跳过网关留 Step 3）：
 * <ul>
 *   <li>{@code USER}  → {@code userTask.assignee = assignee}（sys_user.id 的字符串形式）</li>
 *   <li>{@code ROLE}  → {@code userTask.candidateGroups = [assignee]}（sys_role.role_code）</li>
 *   <li>{@code LEVEL} → 多实例会签：{@code levelResolver.resolveList('L3')} 提供该职级全员用户名集合，逐人生成并行任务（Step 3 注册 bean）</li>
 * </ul>
 */
@Getter
@Setter
public class NodeCfg {

    /** 节点顺序，从 1 开始递增，决定 BPMN 中 userTask 的串联顺序 */
    private Integer seq;

    /** 节点唯一 ID（BPMN 中 userTask 的 id），建议 node_1 / node_2 ... */
    private String nodeId;

    /** 节点显示名（审批节点标题） */
    private String name;

    /** 节点类型：USER / ROLE / LEVEL */
    private String type;

    /**
     * 审批人 / 候选标识，语义随 {@link #type} 变化：
     * <ul>
     *   <li>USER  → sys_user.id（Long 转字符串）</li>
     *   <li>ROLE  → sys_role.role_code</li>
     *   <li>LEVEL → 职级编码（运行时交给 LevelResolver 解析）</li>
     * </ul>
     */
    private String assignee;

    /** 是否允许被驳回（首节点一般 false） */
    private Boolean rejectable;
}
