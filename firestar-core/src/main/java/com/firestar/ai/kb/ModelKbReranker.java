package com.firestar.ai.kb;

import com.firestar.ai.rerank.RerankProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型语义重排（kb.retriever.rerank=model，V214）：把召回命中交给 {@link RerankProvider}
 * 一次批量打分，替代规则重排。与 {@link RuleKbReranker} 条件互斥（model vs rule）。
 *
 * <p><b>优雅降级</b>：provider 未配置凭据或调用异常时，回退到内置规则重排
 * （标题 +5 / heading +3 / 正文每处 +1），确保模型服务不可用时检索不失效，
 * 与 keyword 模式行为一致。每次降级用 warn 记录，便于运维发现配置缺口。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.retriever.rerank", havingValue = "model")
public class ModelKbReranker implements KbReranker {

    private static final Logger log = LoggerFactory.getLogger(ModelKbReranker.class);

    private final RerankProvider provider;
    private final RuleKbReranker fallback = new RuleKbReranker();

    public ModelKbReranker(RerankProvider provider) {
        this.provider = provider;
    }

    @Override
    public double score(KbChunkHit hit, List<String> terms) {
        List<Double> scores = scoreBatch(List.of(hit), terms);
        return scores.isEmpty() ? 0.0 : scores.get(0);
    }

    @Override
    public List<Double> scoreBatch(List<KbChunkHit> hits, List<String> terms) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        // 语义重排用原始 query（terms 空格拼接）作为 query，文档用标题+heading+正文拼接
        String query = String.join(" ", terms == null ? List.of() : terms);
        List<String> texts = hits.stream().map(this::docText).toList();
        try {
            List<Double> scores = provider.rerank(query, texts);
            if (scores == null || scores.size() != hits.size()) {
                throw new IllegalStateException("Rerank 返回分数数 " + (scores == null ? 0 : scores.size())
                        + " != 命中数 " + hits.size());
            }
            return scores;
        } catch (Exception e) {
            log.warn("[KB-RERANK] 模型重排不可用，降级为规则重排: {}", e.getMessage());
            return hits.stream().map(h -> Double.valueOf(fallback.score(h, terms))).toList();
        }
    }

    /** 拼接用于语义重排的文档文本：标题 + heading + 正文（带空格，避免粘连影响语义）。 */
    private String docText(KbChunkHit h) {
        StringBuilder sb = new StringBuilder();
        if (h.title() != null && !h.title().isBlank()) {
            sb.append(h.title()).append(' ');
        }
        if (h.headingPath() != null && !h.headingPath().isBlank()) {
            sb.append(h.headingPath()).append(' ');
        }
        if (h.content() != null) {
            sb.append(h.content());
        }
        return sb.toString();
    }
}
