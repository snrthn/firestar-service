package com.firestar.ai.kb;

import com.firestar.entity.HelpArticle;
import com.firestar.ai.entity.KbChunk;
import com.firestar.ai.entity.KbDoc;
import com.firestar.mapper.HelpArticleMapper;
import com.firestar.ai.mapper.KbChunkMapper;
import com.firestar.ai.mapper.KbDocMapper;
import com.firestar.ai.service.KbTreeRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 关键词召回 + 统一重排检索器（步骤 5 默认实现，设计 §7.1）。
 *
 * <p>召回：
 * <ul>
 *   <li>MD 文档切片：{@code KbChunkMapper.fulltextSearch} 走 MySQL ngram FULLTEXT（BOOLEAN MODE），
 *       仅召回「已发布且切片版本 = 文档当前版本且 status=1」的切片；随后在 Java 侧按
 *       {@code KbTreeRules.isVisible} 做 ACL 过滤（与节点树同口径，ROLE_ADMIN 无条件放行）。</li>
 *   <li>帮助中心文章：{@link HelpArticleSource#buildChunks} 即时切片，对全员可见，无 ACL。</li>
 * </ul>
 * 重排（纯 Java，可单测）：标题命中(+5) > heading 命中(+3) > 正文命中(每处 +1，封顶 10)；
 * 同文档去重保留最高分；最后按 topK 截断。FULLTEXT 召回本身依赖部署期真实 MySQL（ngram），
 * 纯逻辑单测用 mock mapper 覆盖 ACL 过滤 + 重排。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.retriever", havingValue = "keyword", matchIfMissing = true)
public class KeywordRerankRetriever implements KbRetriever {

    private static final int DEFAULT_TOP_K = 4;
    private static final int MAX_TOP_K = 8;

    private final KbChunkMapper chunkMapper;
    private final HelpArticleMapper helpMapper;
    private final KbDocMapper docMapper;
    private final KbReranker reranker;

    /**
     * Spring 注入用构造器（4 参）。rerank 实现由 {@code kb.retriever.rerank} 条件装配，
     * 切换只改配置 + Bean，不改本类。
     */
    @Autowired
    public KeywordRerankRetriever(KbChunkMapper chunkMapper,
                                  HelpArticleMapper helpMapper,
                                  KbDocMapper docMapper,
                                  KbReranker reranker) {
        this.chunkMapper = chunkMapper;
        this.helpMapper = helpMapper;
        this.docMapper = docMapper;
        this.reranker = reranker;
    }

    /**
     * 兼容构造器（3 参）：供单测直接 new 使用，默认绑定规则重排。
     * 生产环境由 Spring 走上面的 4 参构造器。
     */
    public KeywordRerankRetriever(KbChunkMapper chunkMapper,
                                  HelpArticleMapper helpMapper,
                                  KbDocMapper docMapper) {
        this(chunkMapper, helpMapper, docMapper, new RuleKbReranker());
    }

    @Override
    public List<KbChunkHit> retrieve(String query, KbSearchContext ctx, int topK) {
        int k = (topK > 0 ? Math.min(topK, MAX_TOP_K) : DEFAULT_TOP_K);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> terms = extractTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        String booleanQuery = toBooleanQuery(terms);
        Set<String> authorities = (ctx.authorities() == null) ? Set.of() : ctx.authorities();

        // 1) 全量可见文档（KB 规模小，整表载入做 ACL 过滤；与 KbDocService.nodes 同口径）
        Map<Long, KbDoc> docMap = docMapper.selectList(null).stream()
                .collect(Collectors.toMap(KbDoc::getId, d -> d, (a, b) -> a));

        // 2) MD 切片：FULLTEXT 召回（部署期连真实 MySQL 验证） + Java 侧 ACL 过滤
        List<KbChunk> rawChunks = chunkMapper.fulltextSearch(booleanQuery, ctx.tenantId());
        List<KbChunkHit> mdHits = new ArrayList<>();
        for (KbChunk c : rawChunks) {
            KbDoc doc = docMap.get(c.getDocId());
            if (doc == null) {
                continue;
            }
            if (!KbTreeRules.isVisible(doc.getAclRoles(), ctx.admin(), authorities)) {
                continue;
            }
            mdHits.add(new KbChunkHit(
                    c.getDocId(), c.getVersion(), "MD", doc.getTitle(),
                    c.getHeadingPath(), c.getAnchor(), c.getContent(),
                    c.getSourceRef(), 0.0, KbChunkHit.parseFileId(c.getSourceRef())));
        }

        // 3) 帮助中心文章：即时切片（对全员可见，无 ACL）
        List<KbChunkHit> helpHits = HelpArticleSource.buildChunks(
                helpMapper.selectEnabled());

        // 4) 统一重排：重算 score + 过滤无相关 + 降序
        List<KbChunkHit> scored = new ArrayList<>(mdHits);
        scored.addAll(helpHits);
        scored = scored.stream()
                .map(h -> new KbChunkHit(h.docId(), h.version(), h.sourceType(), h.title(),
                        h.headingPath(), h.anchor(), h.content(), h.sourceRef(),
                        reranker.score(h, terms), h.fileId()))
                .filter(h -> h.score() > 0)
                .sorted(Comparator.comparingDouble(KbChunkHit::score).reversed())
                .collect(Collectors.toList());

        // 5) 同文档去重（保留最高分）+ 截 topK
        Map<Long, KbChunkHit> bestPerDoc = new LinkedHashMap<>();
        for (KbChunkHit h : scored) {
            bestPerDoc.merge(h.docId(), h, (oldH, newH) -> oldH.score() >= newH.score() ? oldH : newH);
        }
        return bestPerDoc.values().stream().limit(k).collect(Collectors.toList());
    }

    /** 切词：按空白与标点断，CJK 整段成词（ngram 自然匹配），拉丁词归一小写。 */
    static List<String> extractTerms(String query) {
        List<String> terms = new ArrayList<>();
        for (String raw : query.split("[\\s\\p{Punct}]+")) {
            if (raw.isBlank()) {
                continue;
            }
            terms.add(raw.toLowerCase());
        }
        return terms;
    }

    /** 构造 FULLTEXT BOOLEAN MODE 查询串：去除布尔操作符（+ - ~ > < ( ) * " 等），避免操作符注入。 */
    static String toBooleanQuery(List<String> terms) {
        return String.join(" ", terms);
    }

}
