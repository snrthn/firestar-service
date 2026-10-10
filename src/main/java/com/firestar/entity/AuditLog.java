package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Setter;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_audit_log", autoResultMap = true)
public class AuditLog extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 操作人 ID */
    private Long operatorId;

    /** 操作类型（字典 audit_operation） */
    private String operationType;

    /** 目标对象类型 */
    private String targetType;

    /** 目标对象 ID */
    private Long targetId;

    /** 改前值快照 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> beforeValue;

    /** 改后值快照 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> afterValue;

    /** 操作时间 */
    private LocalDateTime operatedAt;

    /** IP 地址 */
    private String ipAddress;

    /** 备注 */
    private String remark;
}
