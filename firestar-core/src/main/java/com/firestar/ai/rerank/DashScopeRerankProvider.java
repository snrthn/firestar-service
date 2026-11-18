package com.firestar.ai.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.config.EmbeddingProperties;
import com.firestar.ai.core.HttpClientFactory;
import com.firestar.ai.service.ModelCredentialResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 百炼 text-rerank 语义重排（OpenAI 兼容 /rerank 协议，V214）。
 *
 * <p>凭据从 ModelCredentialResolver 的 rerank active 行读取（复用百炼 key，配置行独立）。
 * 未配置 rerank active 行时 {@link #available()} 返回 false，调用方（ModelKbReranker）自动
 * 降级为规则重排，不影响检索可用性。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.retriever.rerank", havingValue = "model")
public class DashScopeRerankProvider implements RerankProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRerankProvider.class);

    private final ModelCredentialResolver credentials;
    private final HttpClientFactory clientFactory;
    private final ObjectMapper mapper;
    private final EmbeddingProperties embedProps;

    public DashScopeRerankProvider(ModelCredentialResolver credentials, HttpClientFactory clientFactory,
                                   ObjectMapper mapper, EmbeddingProperties embedProps) {
        this.credentials = credentials;
        this.clientFactory = clientFactory;
        this.mapper = mapper;
        this.embedProps = embedProps;
    }

    @Override
    public String id() {
        return "dashscope";
    }

    /** rerank active 行是否可用（有 key）。 */
    public boolean available() {
        try {
            ModelCredentialResolver.Credential c = credentials.resolve("rerank");
            return c != null && c.apiKey() != null && !c.apiKey().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<Double> rerank(String query, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        ModelCredentialResolver.Credential cred = credentials.resolve("rerank");
        if (cred == null || cred.apiKey() == null || cred.apiKey().isBlank()) {
            throw new IllegalStateException("Rerank 凭据未配置：请在【模型配置】页配置 rerank 类型的生效行");
        }
        String baseUrl = (cred.baseUrl() == null || cred.baseUrl().isBlank())
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : cred.baseUrl();
        String model = (cred.model() == null || cred.model().isBlank())
                ? "qwen3-rerank" : cred.model();
        try {
            var reqNode = mapper.createObjectNode();
            reqNode.put("model", model);
            reqNode.put("query", query);
            var arr = reqNode.putArray("documents");
            texts.forEach(arr::add);
            reqNode.put("top_n", texts.size()); // 不截断：返回全部候选分数，由上层决定 topK

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/rerank"))
                    .timeout(Duration.ofSeconds(embedProps.getSocketTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cred.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reqNode)))
                    .build();
            HttpResponse<String> resp = clientFactory.newClient(
                            Duration.ofSeconds(embedProps.getConnectTimeoutSeconds()))
                    .send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("Rerank HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                throw new IllegalStateException("Rerank 响应无 results 数组: " + abbreviate(resp.body()));
            }
            double[] scores = new double[texts.size()];
            java.util.Arrays.fill(scores, 0.0);
            for (JsonNode r : results) {
                int idx = r.path("index").asInt(-1);
                if (idx >= 0 && idx < scores.length) {
                    scores[idx] = r.path("relevance_score").asDouble(0.0);
                }
            }
            List<Double> out = new ArrayList<>(scores.length);
            for (double s : scores) {
                out.add(s);
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Rerank 被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("Rerank 失败: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s);
    }
}
