package com.firestar.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.config.EmbeddingProperties;
import com.firestar.ai.core.HttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 Ollama embedding 实现（kb.embedding.provider=local）。
 *
 * <p>数据不出域，契合"高管制度等绝不进第三方"的红线。走 Ollama {@code /api/embed} 批量端点，
 * 文本按 {@code kb.embedding.maxChars} 截断。返回的向量维度必须与配置 dims 一致，否则抛异常
 * （避免写 ES dense_vector 时报更隐蔽的错）。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.embedding.provider", havingValue = "local")
public class LocalOllamaEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalOllamaEmbeddingProvider.class);

    private final EmbeddingProperties props;
    private final HttpClientFactory clientFactory;
    private final ObjectMapper mapper;

    @Autowired
    public LocalOllamaEmbeddingProvider(EmbeddingProperties props,
                                        HttpClientFactory clientFactory,
                                        ObjectMapper mapper) {
        this.props = props;
        this.clientFactory = clientFactory;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return EmbeddingProperties.PROVIDER_LOCAL;
    }

    @Override
    public int dimension() {
        return props.getDims();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            List<String> clipped = texts.stream()
                    .map(t -> t == null ? "" : (t.length() > props.getMaxChars() ? t.substring(0, props.getMaxChars()) : t))
                    .toList();
            var reqNode = mapper.createObjectNode();
            reqNode.put("model", props.getLocalModel());
            var arr = reqNode.putArray("input");
            clipped.forEach(arr::add);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getLocalBaseUrl().replaceAll("/+$", "") + "/api/embed"))
                    .timeout(Duration.ofSeconds(props.getSocketTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reqNode)))
                    .build();
            HttpResponse<String> resp = clientFactory.newClient(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                    .send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("Ollama embed HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode embeddings = root.path("embeddings");
            List<float[]> out = new ArrayList<>(embeddings.size());
            for (JsonNode vec : embeddings) {
                out.add(toVector(vec));
            }
            if (out.size() != clipped.size()) {
                throw new IllegalStateException("Ollama 返回向量数 " + out.size() + " != 输入文本数 " + clipped.size());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama embed 被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("Ollama embed 失败: " + e.getMessage(), e);
        }
    }

    private float[] toVector(JsonNode vec) {
        if (!vec.isArray()) {
            throw new IllegalStateException("Ollama 返回的 embedding 不是数组");
        }
        float[] v = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
            v[i] = (float) vec.get(i).asDouble();
        }
        if (v.length != dimension()) {
            throw new IllegalStateException("Ollama 向量维度 " + v.length + " != 配置 kb.embedding.dims "
                    + dimension() + "（改维需重建 ES 索引）");
        }
        return v;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
