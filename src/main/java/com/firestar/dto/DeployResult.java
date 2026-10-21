package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Flowable 部署结果（门面返回，避免业务层直接触碰 Flowable 类型）。
 */
@Getter
@Setter
public class DeployResult {

    /** Flowable 部署 ID */
    private String deploymentId;

    /** 流程定义 ID（含版本后缀，如 proj_approval:2:uuid） */
    private String processDefinitionId;

    /** 本次部署产出的版本号（同 key 重部署自动 +1） */
    private int version;

    public DeployResult() {
    }

    public DeployResult(String deploymentId, String processDefinitionId, int version) {
        this.deploymentId = deploymentId;
        this.processDefinitionId = processDefinitionId;
        this.version = version;
    }
}
