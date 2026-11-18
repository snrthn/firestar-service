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

    /**
     * 批量重排（V214）：一次给多个命中算分，模型版可一次 HTTP 完成，避免逐条请求。
     * 默认实现逐条调 {@link #score}，规则版无需改动；模型版覆盖此方法走批量 API。
     *
     * @param hits  命中切片（顺序即返回分数的顺序）
     * @param terms 查询切词
     * @return 与 hits 等长、同序的相关度分
     */
    default List<Double> scoreBatch(List<KbChunkHit> hits, List<String> terms) {
        return hits.stream().map(h -> Double.valueOf(score(h, terms))).toList();
    }
}
