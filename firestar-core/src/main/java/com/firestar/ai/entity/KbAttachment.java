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
 * 知识库附件关系（sys_kb_attachment，V207）。
 * role 取值来自字典 kb_attachment_role（V206）：INLINE / REFERENCE / ORIGINAL / EXPIRED。
 *
 * <p>同样不继承 BaseEntity：本表无 ext / version 列，created_by 是 VARCHAR(64)。</p>
 */
@Getter
@Setter
@TableName("sys_kb_attachment")
public class KbAttachment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("doc_id")
    private Long docId;

    /** sys_file_storage.id（biz_type=kb_doc） */
    @TableField("file_id")
    private Long fileId;

    /** 字典 kb_attachment_role */
    @TableField("role")
    private String role;

    @TableField("indexable")
    private Integer indexable;

    @TableField("egress_allowed")
    private Integer egressAllowed;

    /** 转 EXPIRED 的时间 */
    @TableField("expired_at")
    private LocalDateTime expiredAt;

    @TableField("sort_no")
    private Integer sortNo;

    @TableField("created_by")
    private String createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("tenant_id")
    private Long tenantId;
}
