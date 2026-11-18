package com.firestar.ai.embedding;

import java.util.List;

/**
 * 向量化提供者 SPI（P1#5，设计 §7 扩展点）。
 *
 * <p>双实现按 {@code kb.embedding.provider} 条件装配：{@code local}（Ollama，数据不出域）/
 * {@code cloud}（dashscope compatible-mode）。{@code none} 时不装配任何实现，
 * 检索层降级为纯关键字。所有实现必须满足 {@link #dimension()} 与配置 dims 一致，
 * 否则写 ES dense_vector 会失败——故 embed 内部做维度断言。</p>
 */
public interface EmbeddingProvider {

    /** 实现标识（local / cloud），日志与审计用。 */
    String id();

    /** 向量维度，必须与 kb.embedding.dims 一致。 */
    int dimension();

    /**
     * 批量文本向量化，返回与输入一一对应的向量列表。
     *
     * @param texts 待向量化文本（已由调用方截断到 maxChars）
     * @return 每条形如 float[dimension] 的向量，顺序与输入一致
     */
    List<float[]> embed(List<String> texts);

    /** 单条便捷方法。 */
    default float[] embedOne(String text) {
        List<float[]> v = embed(List.of(text));
        return v.isEmpty() ? new float[dimension()] : v.get(0);
    }
}
