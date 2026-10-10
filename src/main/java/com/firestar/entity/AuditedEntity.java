package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 带审计四件套的实体基类 —— 覆盖 25 张表中的 21 张。
 *
 * 例外（无此四字段，直接继承 BaseEntity）：
 *   sys_user_role / sys_role_permission / sys_role_menu / sys_role_conflict
 *   原因：多对多关联表只有"授权"动作，没有"修改"语义，
 *   updated_by/updated_at 永远是 null，属于僵尸字段。
 */
@Getter
@Setter
public abstract class AuditedEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 创建人 ID。登录上下文接入前由业务层显式赋值 */
    private Long createdBy;

    /** 创建时间（自动填充，见 AuditMetaObjectHandler） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新人 ID。登录上下文接入前由业务层显式赋值 */
    private Long updatedBy;

    /**
     * 更新时间（自动填充）。
     * DB 侧另有 ON UPDATE CURRENT_TIMESTAMP 兜底；
     * MP 不支持在 update 时同步改实体字段，需要精确值时按需重查。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
