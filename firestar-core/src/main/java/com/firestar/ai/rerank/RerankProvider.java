package com.firestar.ai.rerank;

import java.util.List;

/**
 * 语义重排提供者 SPI（V214，设计 §7.1 从 rule 扩展到 model）。
 *
 * <p>与 {@link com.firestar.ai.embedding.EmbeddingProvider} 对称：凭据由
 * {@link com.firestar.ai.service.ModelCredentialResolver} 按 model_type=rerank 从库解析，
 * 同一供应商（dashscope）可与 embedding 共用 key，但配置行独立。</p>
 */
public interface RerankProvider {

    /** 实现标识（如 dashscope），日志用。 */
    String id();

    /**
     * 批量语义重排：给定 query 与候选文本，返回与输入一一对应的相关度分。
     *
     * @param query 检索词（原始 query）
     * @param texts 候选文本（标题+正文拼接后的片段）
     * @return 与 texts 等长、同序的相关度分（越大越相关）
     */
    List<Double> rerank(String query, List<String> texts);
}
