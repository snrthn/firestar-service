package com.firestar.ai.kb;

import java.util.List;

/**
 * 知识库重排策略（设计 §7.1 扩展点，配置 kb.retriever.rerank=rule|llm）。
 *
 * <p>召回完成后由本策略给每个命中算相关度分， KeywordRerankRetriever 只负责「召回 → 重排 → 去重 → 截断」链路编排，
 * 不关心分数怎么算。切换实现只改配置 + Bean，不动 ToolExecutor / prompt 契约 / 表结构。</p>
 */
public interface KbReranker {

    /**
     * 给单个命中算相关度分（>0 视为相关，会被保留）。
     *
     * @param hit   命中切片
     * @param terms 查询切词
     * @return 相关度分，越大越相关
     */
    double score(KbChunkHit hit, List<String> terms);
}
