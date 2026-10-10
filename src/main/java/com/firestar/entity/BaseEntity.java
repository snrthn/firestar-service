package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Map;

/**
 * 全表 L1 预留字段基类 —— 25 张表无一例外都有这 4 个字段。
 *
 * 【为什么不把审计四件套也塞进来】
 * V1 基线里有 4 张 RBAC 关联表（sys_user_role / sys_role_permission /
 * sys_role_menu / sys_role_conflict）**没有 updated_by / updated_at**——
 * 关联关系只增不删改，没有"更新"语义。硬塞进来会产生永远为 null 的
 * 僵尸字段并在查询时误导人。所以拆成两层：
 *   BaseEntity     → id / tenantId / version / ext（25 张表全有）
 *   AuditedEntity  → 追加 createdBy / createdAt / updatedBy / updatedAt（21 张表有）
 */
@Getter
@Setter
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 物理主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID（企业）。V1 全部为 0（单租户期）。
     * 将来启用 TenantLineInnerInterceptor 后由插件自动改写 SQL，
     * 业务代码**不需要**、也**不应该**手动 set。
     */
    @TableField("tenant_id")
    private Long tenantId;

    /**
     * 乐观锁版本号。核销/审批等并发写场景必需，MP 会自动 +1 并校验。
     *
     * 【必须 fill = INSERT】不为 null 时，insert 出来的对象 version 是 null，
     * 后续直接 updateById 会让乐观锁插件判定"未启用"而静默放行，
     * 生成的 SQL 里既没有 SET version，也没有 WHERE version。
     * 这是实测踩到的坑，详见 AuditMetaObjectHandler 的说明。
     */
    @Version
    @TableField(value = "version", fill = FieldFill.INSERT)
    private Integer version;

    /**
     * 扩展字段（JSON）。
     *
     * 【禁令】禁止放：金额、状态、需要 WHERE/索引/报表聚合的字段。
     * 原因：JSON 字段无法建索引、无法参与聚合，放进去等于埋雷。
     * 详见 docs/建表方案.md §4 L2 层扩展机制。
     *
     * JacksonTypeHandler 让 Map 自动与 JSON 列互转；
     * 依赖 @TableName(autoResultMap = true) 才生效，含 JSON 业务列的实体已标注。
     */
    @TableField(value = "ext", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> ext;
}
