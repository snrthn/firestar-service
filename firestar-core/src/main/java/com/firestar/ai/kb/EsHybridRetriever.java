package com.firestar.ai.kb;

import com.firestar.ai.config.EsProperties;
import com.firestar.ai.embedding.EmbeddingProvider;
import com.firestar.ai.entity.KbDoc;
import com.firestar.ai.es.EsVectorStore;
import com.firestar.ai.mapper.KbDocMapper;
import com.firestar.ai.service.KbTreeRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ES 混合检索器（P1#5-c）：MySQL ngram 关键字 + ES kNN 向量，Java 端 RRF 融合。
 *
 * <p><b>复用而非重写</b>：继承 {@link KeywordRerankRetriever}，关键字路（全量 ACL 过滤 +
 * FULLTEXT 召回 + 规则重排 + 帮助文章 + 同文档去重）直接调 {@code super.retrieve()}，
 * 本类只补向量路 + RRF 融合——ACL 与出域链路一行不重复、不可绕过。</p>
 *
 * <p><b>装配</b>：{@code kb.retriever=hybrid} 且容器有 EmbeddingProvider + EsVectorStore 时才创建；
 * 与 keyword 版条件互斥，保证容器里只有一个 KbRetriever Bean。ES/embedding 任一异常时
 * 优雅降级为纯关键字结果（超参数 k 由 RRF 常量固定，融合权重关键字 1.0 / 向量 1.0）。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.retriever", havingValue = "hybrid")
public class EsHybridRetriever extends KeywordRerankRetriever {

    private static final Logger log = LoggerFactory.getLogger(EsHybridRetriever.class);

    private final EmbeddingProvider embeddingProvider;
    private final EsVectorStore vectorStore;
    private final KbDocMapper docMapper;
    private final EsProperties esProps;

    /** 关键字路召回放大系数：融合前每路取更多候选，RRF 才有得选。 */
    private static final int RECALL_FACTOR = 2;

    @Autowired
    public EsHybridRetriever(KbDocMapper docMapper,
                             com.firestar.ai.mapper.KbChunkMapper chunkMapper,
                             com.firestar.mapper.HelpArticleMapper helpMapper,
                             KbReranker reranker,
                             EmbeddingProvider embeddingProvider,
                             EsVectorStore vectorStore,
                             EsProperties esProps) {
        super(chunkMapper, helpMapper, docMapper, reranker);
        this.docMapper = docMapper;
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
        this.esProps = esProps;
    }

    @Override
    public List<KbChunkHit> retrieve(String query, KbSearchContext ctx, int topK) {
        int k = (topK > 0 ? Math.min(topK, 8) : 4);
        // 1) 关键字路（复用已验证实现：FULLTEXT + ACL + 重排 + 帮助文章 + 去重）
        List<KbChunkHit> keywordHits = super.retrieve(query, ctx, k * RECALL_FACTOR);
        if (query == null || query.isBlank()) {
            return keywordHits;
        }

        // 2) 向量路：query 向量化 → ES kNN → docMap 做 ACL 过滤（与关键字路同口径）
        List<EsVectorStore.VectorHit> vectorHits;
        try {
            float[] qv = embeddingProvider.embedOne(query);
            vectorHits = vectorStore.knnSearch(qv, Math.min(k * RECALL_FACTOR, esProps.getCandidates()));
        } catch (Exception e) {
            log.warn("[KB-HYBRID] 向量路不可用，降级为纯关键字: {}", e.getMessage());
            return keywordHits;
        }

        // 3) ACL 过滤向量命中（ROLE_ADMIN 放行 / 逗号分隔 / PERM: 去前缀，复用 KbTreeRules）
        Set<String> authorities = ctx.authorities() == null ? Set.of() : ctx.authorities();
        Map<Long, KbDoc> docMap = docMapper.selectList(null).stream()
                .collect(Collectors.toMap(KbDoc::getId, d -> d, (a, b) -> a));
        Map<Long, KbChunkHit> vectorByDoc = new LinkedHashMap<>();
        for (EsVectorStore.VectorHit vh : vectorHits) {
            KbDoc doc = docMap.get(vh.docId());
            if (doc == null || !KbTreeRules.isVisible(doc.getAclRoles(), ctx.admin(), authorities)) {
                continue;
            }
            KbChunkHit hit = new KbChunkHit(vh.docId(), vh.version(), "MD", doc.getTitle(),
                    vh.headingPath(), extractAnchor(vh.sourceRef()), vh.content(),
                    vh.sourceRef(), 0.0, KbChunkHit.parseFileId(vh.sourceRef()));
            vectorByDoc.putIfAbsent(vh.docId(), hit);
        }

        // 4) RRF 融合：两路各按 docId 建候选（rank 从 1 起），融合出 score
        List<RrfFusion.Candidate> kwCands = new ArrayList<>();
        for (int i = 0; i < keywordHits.size(); i++) {
            kwCands.add(new RrfFusion.Candidate(keywordHits.get(i).docId(), i + 1, 1.0));
        }
        List<RrfFusion.Candidate> vecCands = new ArrayList<>();
        int vrank = 1;
        for (Long docId : vectorByDoc.keySet()) {
            vecCands.add(new RrfFusion.Candidate(docId, vrank++, 1.0));
        }
        Map<Long, Double> fused = RrfFusion.fuse(List.of(kwCands, vecCands));

        // 5) 用融合分作为最终 score，命中内容优先取关键字路（带标题/重排信息全），向量路补充新 doc
        Map<Long, KbChunkHit> kwByDoc = new LinkedHashMap<>();
        for (KbChunkHit h : keywordHits) {
            kwByDoc.putIfAbsent(h.docId(), h);
        }
        List<KbChunkHit> result = new ArrayList<>();
        for (Map.Entry<Long, Double> e : fused.entrySet()) {
            KbChunkHit base = kwByDoc.get(e.getKey());
            if (base == null) {
                base = vectorByDoc.get(e.getKey());
            }
            if (base == null) {
                continue;
            }
            result.add(new KbChunkHit(base.docId(), base.version(), base.sourceType(), base.title(),
                    base.headingPath(), base.anchor(), base.content(), base.sourceRef(),
                    e.getValue(), base.fileId()));
            if (result.size() >= k) {
                break;
            }
        }
        log.debug("[KB-HYBRID] 融合完成 kw={} vec={} fused={} topK={}",
                keywordHits.size(), vectorByDoc.size(), fused.size(), result.size());
        return result;
    }

    /** 从 sourceRef（kb:doc/{id}/v{ver}/{anchor}）反解 anchor，取最后一段。 */
    private static String extractAnchor(String sourceRef) {
        if (sourceRef == null) {
            return "";
        }
        String[] parts = sourceRef.split("/");
        return parts.length >= 4 ? parts[parts.length - 1] : "";
    }
}
