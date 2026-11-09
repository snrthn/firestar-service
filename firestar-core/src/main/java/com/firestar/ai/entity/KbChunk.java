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
 * 知识库切片（sys_kb_chunk，V207，带 ngram FULLTEXT）。
 *
 * <p>步骤 4.5 只用到「发布时把旧版本切片置 status=0」；真正的切片器与检索器属于步骤 5，
 * 故本轮不写生成逻辑，但实体先落地，避免步骤 5 再回头改表映射。</p>
 */
@Getter
@Setter
@TableName("sys_kb_chunk")
public class KbChunk implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("doc_id")
    private Long docId;

    @TableField("version")
    private Integer version;

    @TableField("seq")
    private Integer seq;

    @TableField("heading_path")
    private String headingPath;

    @TableField("anchor")
    private String anchor;

    @TableField("content")
    private String content;

    @TableField("token_cnt")
    private Integer tokenCnt;

    @TableField("source_ref")
    private String sourceRef;

    /** 向量库引用（预留，P0 为空） */
    @TableField("embedding_ref")
    private String embeddingRef;

    /** NONE | PENDING | READY | FAILED（预留，P0 恒 NONE） */
    @TableField("vector_status")
    private String vectorStatus;

    /** 1 有效 / 0 失效 */
    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("tenant_id")
    private Long tenantId;
}
