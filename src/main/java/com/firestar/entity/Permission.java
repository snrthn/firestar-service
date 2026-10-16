package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_permission")
public class Permission extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 权限编码 */
    private String permCode;

    /** 权限名称 */
    private String permName;

    /** 资源类型（字典 permission_resource） */
    private String resourceType;

    /** 操作类型（字典 permission_action） */
    private String actionType;

    /** 父权限 ID（自引用） */
    private Long parentId;

    /** 状态（字典 common_status） */
    private String status;
}
