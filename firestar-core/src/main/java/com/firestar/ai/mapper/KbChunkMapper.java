package com.firestar.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.ai.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 知识库切片。步骤 4.5 仅用于发布时失效旧版本切片；切片生成属步骤 5。 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * ngram FULLTEXT 召回（步骤 5，设计 §7.1）。
     *
     * <p>仅召回「切片有效(status=1) + 所属文档已发布(PUBLISHED) + 切片版本 = 文档当前版本」的切片；
     * ACL 行级过滤在 Java 侧按 {@code KbTreeRules.isVisible} 完成（本 SQL 不携带 acl_roles，
     * 由调用方 load 全量 doc 后过滤）。依赖 MySQL 5.7 ngram 解析器（部署期验证，非单测覆盖）。</p>
     */
    @Select("SELECT c.id, c.doc_id, c.version, c.seq, c.heading_path, c.anchor, c.content, " +
            "c.token_cnt, c.source_ref, c.embedding_ref, c.vector_status, c.status, " +
            "c.created_at, c.tenant_id " +
            "FROM sys_kb_chunk c " +
            "INNER JOIN sys_kb_doc d ON d.id = c.doc_id " +
            "WHERE c.tenant_id = #{tenantId} AND c.status = 1 " +
            "  AND d.status = 'PUBLISHED' AND c.version = d.version " +
            "  AND MATCH(c.content, c.heading_path) AGAINST (#{q} IN BOOLEAN MODE)")
    List<KbChunk> fulltextSearch(@Param("q") String booleanQuery, @Param("tenantId") Long tenantId);
}
