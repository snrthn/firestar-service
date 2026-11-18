package com.firestar.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.config.EmbeddingProperties;
import com.firestar.ai.core.HttpClientFactory;
import com.firestar.ai.service.ModelCredentialResolver;
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
import java.util.Comparator;
import java.util.List;

/**
 * 云端 embedding 实现（kb.embedding.provider=cloud），dashscope compatible-mode 协议。
 *
 * <p><b>出域提醒</b>：此实现会把切片正文发送到第三方，与"高管薪酬制度等绝不进第三方"红线冲突。
 * 启用前须由运维按知识库 ACL 白名单确认哪些库可用；本类不内置判断（策略归 KbTreeRules/出域策略）。
 * 密钥加密存储，由 {@link ModelCredentialResolver} 统一解密。</p>
 *
 * <p><b>凭据入池</b>：base_url / model / api_key 一律从 sys_model_config 的 embedding 生效行读取
 * （经 Resolver 解密）；DB 无生效行 = 未配置，直接报错（凭据由管理员在【模型配置】页录入）。
 * dims 仍由 {@code kb.embedding.dims} 决定（ES 索引定死，改维须重建），不受 DB 切换影响。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.embedding.provider", havingValue = "cloud")
public class CloudEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudEmbeddingProvider.class);

    private final EmbeddingProperties props;
    private final HttpClientFactory clientFactory;
    private final ModelCredentialResolver credentialResolver;
    private final ObjectMapper mapper;

    @Autowired
    public CloudEmbeddingProvider(EmbeddingProperties props,
                                   HttpClientFactory clientFactory,
                                   ModelCredentialResolver credentialResolver,
                                   ObjectMapper mapper) {
        this.props = props;
        this.clientFactory = clientFactory;
        this.credentialResolver = credentialResolver;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return EmbeddingProperties.PROVIDER_CLOUD;
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
            // 凭据只认 DB 的 embedding 生效行（管理员在【模型配置】页录入）
            ModelCredentialResolver.Credential cred = credentialResolver.resolve("embedding");
            if (cred == null || cred.apiKey() == null || cred.apiKey().isBlank()) {
                throw new IllegalStateException("Embedding 凭据未配置：请在【模型配置】页录入 model_type=embedding 的配置行"
                        + "（Base URL / 模型名 / API Key）并设为生效");
            }
            // 维度护栏：DB 配置行若声明了 dims 且与 kb.embedding.dims（建索引时定死）不一致，
            // 说明换了不同维度的模型 —— fail-fast，避免写 ES 时维度不匹配才报错。
            String dimsInDb = cred.extraStr("dims", null);
            if (dimsInDb != null && !dimsInDb.equals(String.valueOf(props.getDims()))) {
                throw new IllegalStateException("Embedding 模型维度不一致：配置行 dims=" + dimsInDb
                        + " 但 kb.embedding.dims=" + props.getDims()
                        + "。ES 索引维度在建索引时定死，切换不同维度模型须先删除并重建索引"
                        + "（kb.es.index），再同步修改 kb.embedding.dims 后重启。");
            }
            List<String> clipped = texts.stream()
                    .map(t -> t == null ? "" : (t.length() > props.getMaxChars() ? t.substring(0, props.getMaxChars()) : t))
                    .toList();
            var reqNode = mapper.createObjectNode();
            reqNode.put("model", cred.model());
            var arr = reqNode.putArray("input");
            clipped.forEach(arr::add);

            String apiKey = cred.apiKey();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cred.baseUrl().replaceAll("/+$", "") + "/embeddings"))
                    .timeout(Duration.ofSeconds(props.getSocketTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reqNode)))
                    .build();
            HttpResponse<String> resp = clientFactory.newClient(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                    .send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("Cloud embed HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new IllegalStateException("Cloud embed 响应无 data 数组: " + abbreviate(resp.body()));
            }
            // 按 index 排序还原顺序（dashscope data 带 index 字段）
            List<JsonNode> items = new ArrayList<>();
            data.forEach(items::add);
            items.sort(Comparator.comparingInt(n -> n.path("index").asInt(0)));
            List<float[]> out = new ArrayList<>(items.size());
            for (JsonNode item : items) {
                out.add(toVector(item.path("embedding")));
            }
            if (out.size() != clipped.size()) {
                throw new IllegalStateException("Cloud 返回向量数 " + out.size() + " != 输入 " + clipped.size());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cloud embed 被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("Cloud embed 失败: " + e.getMessage(), e);
        }
    }

    private float[] toVector(JsonNode vec) {
        if (!vec.isArray()) {
            throw new IllegalStateException("Cloud 返回的 embedding 不是数组");
        }
        float[] v = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
            v[i] = (float) vec.get(i).asDouble();
        }
        if (v.length != dimension()) {
            throw new IllegalStateException("Cloud 向量维度 " + v.length + " != 配置 kb.embedding.dims "
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
