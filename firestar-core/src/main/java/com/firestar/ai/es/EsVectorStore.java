package com.firestar.ai.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.firestar.ai.config.EsProperties;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ES 向量库（P1#5）：只做 dense_vector 存取与 kNN 召回，关键字不进 ES。
 *
 * <p><b>条件装配</b>：{@code kb.retriever=hybrid} 时才创建，默认 keyword 模式零 ES 依赖。
 * <b>优雅降级</b>：所有操作抛异常时由调用方捕获降级为纯关键字，ES 挂掉不影响知识库可用性。
 * 索引映射在首次写入时按 {@code kb.embedding.dims} 建（dims 由 EmbeddingProperties 注入），
 * 已存在索引不重建（改维需人工删索引）。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.retriever", havingValue = "hybrid")
public class EsVectorStore {

    private static final Logger log = LoggerFactory.getLogger(EsVectorStore.class);

    private final EsProperties props;
    private final int dims;
    private final ElasticsearchClient client;
    private volatile boolean indexEnsured = false;

    @Autowired
    public EsVectorStore(EsProperties props, com.firestar.ai.config.EmbeddingProperties embeddingProps) {
        this.props = props;
        this.dims = embeddingProps.getDims();
        props.validate();
        RestClient restClient = RestClient.builder(new HttpHost(props.getHost(), props.getPort(), props.getScheme()))
                .build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        this.client = new ElasticsearchClient(transport);
        log.info("[KB] ES 向量库客户端初始化: {}:{} index={} dims={}",
                props.getHost(), props.getPort(), props.getIndex(), dims);
    }

    /** 供单测注入 mock client 用。 */
    EsVectorStore(EsProperties props, int dims, ElasticsearchClient client) {
        this.props = props;
        this.dims = dims;
        this.client = client;
    }

    public String index() {
        return props.getIndex();
    }

    /** 首次写入前确保索引存在（dense_vector dims 与配置对齐）。线程安全，只建一次。 */
    public synchronized void ensureIndex() throws IOException {
        if (indexEnsured) {
            return;
        }
        boolean exists = client.indices().exists(e -> e.index(props.getIndex())).value();
        if (!exists) {
            client.indices().create(c -> c.index(props.getIndex()).mappings(m -> m
                    .properties("docId", p -> p.long_(l -> l))
                    .properties("version", p -> p.integer(i -> i))
                    .properties("chunkId", p -> p.long_(l -> l))
                    .properties("headingPath", p -> p.text(t -> t))
                    .properties("content", p -> p.text(t -> t))
                    .properties("sourceRef", p -> p.keyword(k -> k))
                    .properties("embedding", p -> p.denseVector(d -> d.dims(dims).index(true).similarity("cosine")))
            ));
            log.info("[KB] ES 向量索引已创建: {} dims={}", props.getIndex(), dims);
        }
        indexEnsured = true;
    }

    /** 写入/覆盖一条切片向量。文档 id = chunkId，天然幂等。 */
    public void upsert(long chunkId, long docId, int version, String headingPath,
                       String content, String sourceRef, float[] embedding) throws IOException {
        ensureIndex();
        if (embedding == null || embedding.length != dims) {
            throw new IllegalArgumentException("embedding 维度 " + (embedding == null ? "null" : embedding.length)
                    + " != " + dims);
        }
        var doc = new java.util.LinkedHashMap<String, Object>();
        doc.put("docId", docId);
        doc.put("version", version);
        doc.put("chunkId", chunkId);
        doc.put("headingPath", headingPath == null ? "" : headingPath);
        doc.put("content", content == null ? "" : content);
        doc.put("sourceRef", sourceRef == null ? "" : sourceRef);
        doc.put("embedding", toList(embedding));
        client.index(i -> i.index(props.getIndex()).id(String.valueOf(chunkId)).document(doc));
    }

    /** kNN 召回，返回按向量相似度降序的 chunkId + 相似度分。 */
    public List<VectorHit> knnSearch(float[] queryVector, int topK) throws IOException {
        if (queryVector == null || queryVector.length != dims) {
            throw new IllegalArgumentException("queryVector 维度非法");
        }
        SearchResponse<Map> resp = client.search(s -> s
                        .index(props.getIndex())
                        .knn(k -> k.field("embedding").queryVector(toList(queryVector))
                                .k((long) topK).numCandidates((long) Math.max(props.getCandidates(), topK)))
                        .size(topK),
                Map.class);
        List<VectorHit> out = new ArrayList<>();
        for (Hit<Map> h : resp.hits().hits()) {
            Map<String, Object> src = h.source();
            if (src == null) {
                continue;
            }
            long chunkId = num(src.get("chunkId"));
            long docId = num(src.get("docId"));
            int version = (int) num(src.get("version"));
            if (chunkId <= 0) {
                continue;
            }
            out.add(new VectorHit(chunkId, docId, version,
                    str(src.get("headingPath")), str(src.get("content")),
                    str(src.get("sourceRef")), h.score() == null ? 0 : h.score()));
        }
        return out;
    }

    /** 删除某文档的全部向量（发布新版本前清旧、删文档时清库）。 */
    public void deleteByDocId(long docId) throws IOException {
        // 首次向量化冷启动：索引尚未建立时 deleteByQuery 会 404 中断整个向量化流程
        // （清旧向量→embed→upsert 的第一步就失败）。此处先 ensureIndex 让首次自动建索引，
        // 与 upsert 保持同一套"首次写入自动建"语义，避免死锁。
        ensureIndex();
        client.deleteByQuery(d -> d.index(props.getIndex())
                .query(q -> q.term(t -> t.field("docId").value(docId))));
    }

    /** ES 向量命中（还未经 ACL / 重排）。score 为向量相似度。 */
    public record VectorHit(long chunkId, long docId, int version, String headingPath,
                            String content, String sourceRef, double score) {
    }

    private static List<Float> toList(float[] arr) {
        List<Float> l = new ArrayList<>(arr.length);
        for (float f : arr) {
            l.add(f);
        }
        return l;
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
