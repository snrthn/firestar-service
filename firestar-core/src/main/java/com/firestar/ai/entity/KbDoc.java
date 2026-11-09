package com.firestar.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档 / 文件夹节点（sys_kb_doc，V207 建表 + V208 加树形四列）。
 *
 * <p><b>⚠️ 刻意不继承 BaseEntity / AuditedEntity</b>（2026-11-09 步骤 4.5 核过的坑）：
 * <ul>
 *   <li>BaseEntity 有 {@code ext}（JSON + JacksonTypeHandler）与 {@code tenantId / @Version version}，
 *       而 sys_kb_doc <b>没有 ext 列</b>，且 {@code version} 是「文档业务版本」（发布 v+1 用），
 *       <b>不是乐观锁</b>。继承会让 MP 生成 {@code INSERT INTO ... (ext, ...)} 直接报未知列，
 *       乐观锁插件还会擅自给业务版本号 +1；</li>
 *   <li>AuditedEntity 的 createdBy/updatedBy 是 {@code Long}，而本表是 {@code VARCHAR(64)}（存用户名），
 *       类型对不上。</li>
 * </ul>
 * 因此与 {@link AiPromptConfig} 一样独立实现 Serializable，字段逐一对应 DDL。</p>
 *
 * <p>节点同构（方案 A）：DOC 与 FOLDER 共用一行，FOLDER 的 contentMd 恒为 null。</p>
 */
@Getter
@Setter
@TableName("sys_kb_doc")
public class KbDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("title")
    private String title;

    /** 摘要 / 切片前置上下文 */
    @TableField("summary")
    private String summary;

    /** Markdown 正文，图片只允许 file:&lt;fileId&gt; 协议 */
    @TableField("content_md")
    private String contentMd;

    /** 可见角色标签，逗号分隔；NULL = 全员可见 */
    @TableField("acl_roles")
    private String aclRoles;

    /** DRAFT | PENDING_REVIEW | PUBLISHED | ARCHIVED（PENDING_REVIEW 为 Flowable 预留位） */
    @TableField("status")
    private String status;

    /** 业务版本：发布时 +1；非乐观锁，不带 @Version */
    @TableField("version")
    private Integer version;

    /** MD | HELP_ARTICLE | FILE | URL */
    @TableField("source_type")
    private String sourceType;

    @TableField("source_ref")
    private String sourceRef;

    /** 内容级出域准入预留位，P0 恒 1 且不参与判定（AllowAllKbEgressPolicy） */
    @TableField("egress_allowed")
    private Integer egressAllowed;

    @TableField("published_at")
    private LocalDateTime publishedAt;

    @TableField("published_by")
    private String publishedBy;

    @TableField("created_by")
    private String createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("tenant_id")
    private Long tenantId;

    // ===== V208 树形四列 =====

    /** 父节点 ID，0 = 根 */
    @TableField("parent_id")
    private Long parentId;

    /** DOC | FOLDER（Java enum 硬编码，不占字典行） */
    @TableField("node_type")
    private String nodeType;

    /** 同级排序号，小在前 */
    @TableField("sort_no")
    private Integer sortNo;

    /** 物化路径 /1/3/7/，面包屑 / 子树查询 / 权限继承 / 防环共用 */
    @TableField("path")
    private String path;
}
