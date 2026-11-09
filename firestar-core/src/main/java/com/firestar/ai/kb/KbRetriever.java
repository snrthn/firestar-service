package com.firestar.ai.kb;

import java.util.List;

/**
 * 知识库检索器接口（步骤 5，设计 §7）。
 *
 * <p>实现约定：召回 + ACL 过滤 + 重排，返回按相关度降序的 {@link KbChunkHit}。
 * 首个实现 {@link KeywordRerankRetriever} 用 MySQL ngram FULLTEXT 召回 MD 文档切片 +
 * 帮助中心文章即时切片，再用统一规则在 Java 侧重排（标题/heading 命中加权 > 正文命中、
 * 同文档去重、topK 截断）。</p>
 */
public interface KbRetriever {

    /**
     * 检索知识库。
     *
     * @param query 用户问题 / 关键词
     * @param ctx   检索上下文（ACL 身份，复用 {@code KbTreeRules.isVisible} 口径）
     * @param topK  返回条数，<=0 取默认 4
     * @return 已做 ACL 过滤 + 重排的命中列表（可能为空）
     */
    List<KbChunkHit> retrieve(String query, KbSearchContext ctx, int topK);
}
