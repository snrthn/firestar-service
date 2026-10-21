package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Setter;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.firestar.dto.NodeCfg;
import java.util.List;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_process_def", autoResultMap = true)
public class ProcessDef extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 流程编码（业务键） */
    private String processCode;

    /** 流程名称 */
    private String processName;

    /** Flowable 部署 ID */
    private String flowableDeploymentId;

    /** 版本号 */
    private Integer defVersion;

    /** 状态（字典 common_status） */
    private String status;

    /** 节点列表（JSON 数组：[NodeCfg, ...]，由 JacksonTypeHandler 与 List<NodeCfg> 互转） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<NodeCfg> nodeList;

    /** 备注 */
    private String remark;
}
