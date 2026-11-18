package com.firestar.ai.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1#5 ES 兼容性探针：用 Boot 3.3.4 自带的 ES client 8.13.4 连真实 ES（8.19.x），
 * 验证连接 / 向量索引 / kNN 召回 / 混合融合是否可用，以及 Basic 许可证下的能力边界。
 *
 * <p>该测试依赖本机 ES（127.0.0.1:9200），运行会在 ES 上创建临时索引
 * {@code firestar_compat_probe} 并在结束时删除，不触碰业务数据。</p>
 *
 * <p><b>默认禁用</b>（CI 通常无 ES）：需显式加 {@code -Des.probe=true} 才执行，
 * 真机复现命令：{@code mvn -pl firestar-core -am -Des.probe=true -Dtest=EsCompatibilityProbeTest test}。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "es.probe", matches = "true")
class EsCompatibilityProbeTest {

    private static final String INDEX = "firestar_compat_probe";
    private static ElasticsearchClient client;

    @BeforeAll
    static void setUp() {
        RestClient restClient = RestClient.builder(new HttpHost("127.0.0.1", 9200, "http")).build();
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
        try {
            client.indices().delete(d -> d.index(INDEX));
        } catch (Exception ignored) {
            // 索引不存在，忽略
        }
    }

    @Test
    @Order(1)
    @DisplayName("1. client 8.13.4 连接 ES 8.19.x（验证版本向后兼容）")
    void connect() throws IOException {
        String version = client.info().version().number();
        System.out.println("[PROBE] ES server version = " + version);
        assertTrue(version.startsWith("8.19."), "期望连到 ES 8.19.x，实际 " + version);
    }

    @Test
    @Order(2)
    @DisplayName("2. 建向量索引（dense_vector + cosine + text）")
    void createIndex() throws IOException {
        var resp = client.indices().create(c -> c
                .index(INDEX)
                .mappings(m -> m
                        .properties("content", p -> p.text(t -> t.analyzer("standard")))
                        .properties("embedding", p -> p.denseVector(d -> d.dims(3).index(true).similarity("cosine")))
                )
        );
        assertTrue(resp.acknowledged());
        System.out.println("[PROBE] 向量索引创建成功");
    }

    @Test
    @Order(3)
    @DisplayName("3. 写入测试文档（含 3 维向量）")
    void indexDocs() throws IOException {
        client.index(i -> i.index(INDEX).id("1").document(Map.of(
                "content", "飞星报销差旅费怎么申请",
                "embedding", List.of(1.0f, 0.0f, 0.0f))));
        client.index(i -> i.index(INDEX).id("2").document(Map.of(
                "content", "差旅费报销标准与审批流程",
                "embedding", List.of(0.0f, 1.0f, 0.0f))));
        client.index(i -> i.index(INDEX).id("3").document(Map.of(
                "content", "工资条查看路径",
                "embedding", List.of(0.0f, 0.0f, 1.0f))));
        client.indices().refresh(r -> r.index(INDEX));
        System.out.println("[PROBE] 3 条测试文档写入并 refresh");
    }

    @Test
    @Order(4)
    @DisplayName("4. kNN 向量召回（8.13.4 client 的 KnnQuery 打 ES 8.19）")
    void knnSearch() throws IOException {
        SearchResponse<Map> resp = client.search(s -> s
                        .index(INDEX)
                        .knn(k -> k.field("embedding").queryVector(List.of(1.0f, 0.0f, 0.0f))
                                .k(2L).numCandidates(10L))
                        .size(5),
                Map.class);
        assertFalse(resp.hits().hits().isEmpty(), "kNN 应有命中");
        System.out.println("[PROBE] kNN 命中 " + resp.hits().hits().size() + " 条，首条 id="
                + resp.hits().hits().get(0).id());
    }

    @Test
    @Order(5)
    @DisplayName("5. Basic 许可证下 RRF 的边界探测（预期因许可证受限）")
    void rrfLicenseBoundary() throws IOException {
        try {
            SearchResponse<Map> resp = client.search(s -> s
                            .index(INDEX)
                            .query(q -> q.match(m -> m.field("content").query("报销")))
                            .knn(k -> k.field("embedding").queryVector(List.of(0.0f, 1.0f, 0.0f))
                                    .k(2L).numCandidates(10L))
                            .rank(r -> r.rrf(rr -> rr.rankConstant(60L)))
                            .size(5),
                    Map.class);
            System.out.println("[PROBE] RRF 可用，命中 " + resp.hits().hits().size() + " 条");
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            String msg = String.valueOf(e.getMessage());
            System.out.println("[PROBE] RRF 受许可证限制: " + msg);
            assertTrue(msg.contains("license") || msg.contains("non-compliant"),
                    "RRF 失败应明确是许可证原因，实际: " + msg);
        }
    }

    @Test
    @Order(6)
    @DisplayName("6. 免费替代方案：kNN + BM25 两路查询在 Java 端加权融合")
    void javaSideHybrid() throws IOException {
        // 路 A：BM25 关键字召回
        SearchResponse<Map> bm25 = client.search(s -> s
                        .index(INDEX)
                        .query(q -> q.match(m -> m.field("content").query("报销")))
                        .size(5),
                Map.class);
        // 路 B：kNN 向量召回
        SearchResponse<Map> knn = client.search(s -> s
                        .index(INDEX)
                        .knn(k -> k.field("embedding").queryVector(List.of(0.0f, 1.0f, 0.0f))
                                .k(2L).numCandidates(10L))
                        .size(5),
                Map.class);

        // Java 端 RRF 融合（无需 ES 许可证）：score = Σ 1/(60 + rank)
        Map<String, Double> fused = new LinkedHashMap<>();
        fuseRrf(bm25.hits().hits(), fused);
        fuseRrf(knn.hits().hits(), fused);
        List<Map.Entry<String, Double>> ranked = fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());

        assertFalse(ranked.isEmpty(), "Java 端融合应有结果");
        System.out.println("[PROBE] Java 端 RRF 融合成功，候选 " + ranked.size() + " 条，Top1 id="
                + ranked.get(0).getKey() + " score=" + String.format("%.4f", ranked.get(0).getValue()));
    }

    private static void fuseRrf(List<Hit<Map>> hits, Map<String, Double> fused) {
        int rank = 1;
        for (Hit<Map> h : hits) {
            fused.merge(h.id(), 1.0 / (60 + rank), Double::sum);
            rank++;
        }
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (client != null) {
            client.indices().delete(d -> d.index(INDEX));
            System.out.println("[PROBE] 测试索引已清理");
        }
    }
}
