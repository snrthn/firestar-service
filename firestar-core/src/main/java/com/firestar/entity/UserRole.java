package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_user_role")
public class UserRole extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 用户 ID → sys_user.id */
    private Long userId;

    /** 角色 ID → sys_role.id */
    private Long roleId;

    /** 授权人 ID */
    private Long grantedBy;

    /** 授权时间 */
    private LocalDateTime grantedAt;
}
