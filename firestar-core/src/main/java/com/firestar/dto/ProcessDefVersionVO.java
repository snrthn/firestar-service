package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 流程定义版本视图（投影自 Flowable ACT_RE_PROCDEF）。
 */
@Getter
@Setter
public class ProcessDefVersionVO {

    /** 版本号 */
    private int version;

    /** 流程定义 ID */
    private String processDefinitionId;

    /** 部署 ID */
    private String deploymentId;

    /** 流程名称 */
    private String name;

    /** 是否挂起（停用态对应 suspended=true） */
    private boolean suspended;

    /** 部署时间（ISO 字符串） */
    private String deploymentTime;
}
