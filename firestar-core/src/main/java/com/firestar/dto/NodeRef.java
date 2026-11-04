package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 流程节点精简引用（审批中心驳回下拉用）。
 *
 * <p>前端驳回某任务时，从该任务携带的 {@code rejectTargets} 列表中选择「退回目标节点」，
 * 无需自行掌握流程内部节点 id。</p>
 */
@Getter
@Setter
public class NodeRef {

    /** 节点 id（= Flowable taskDefinitionKey，作为 reject 的 toNode） */
    private String nodeId;

    /** 节点名称（取自流程定义 node_list 的 name） */
    private String nodeName;

    public NodeRef() {
    }

    public NodeRef(String nodeId, String nodeName) {
        this.nodeId = nodeId;
        this.nodeName = nodeName;
    }
}
