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
@TableName(value = "sys_role_permission")
public class RolePermission extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 角色 ID → sys_role.id */
    private Long roleId;

    /** 权限 ID → sys_permission.id */
    private Long permissionId;

    /** 状态（字典 common_status） */
    private String status;
}
