package com.firestar.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.crypto.CryptoService;
import com.firestar.ai.entity.ModelConfig;
import com.firestar.ai.mapper.ModelConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 模型凭据统一解析器：按 model_type 从 sys_model_config 的 active 行解析出运行期配置，
 * 敏感字段经 {@link CryptoService} 解密，extra_json 解析为 Map。
 *
 * <p><b>DB 是唯一真源</b>（2026-11-18 起）：四类模型（chat / embedding / rerank / tts）的
 * 端点、模型名、密钥一律由系统管理员在【模型配置】页录入，写入时即加密落库。
 * 本类<b>不再</b>提供 properties / 环境变量兜底，也没有启动期回填 ——
 * 目的是消除"两处 key、谁生效说不清"的历史包袱（同一把百炼 key 曾同时存在于
 * application.properties 与 sys_model_config，改一处忘另一处就会踩坑）。</p>
 *
 * <p>dims 不在这里管理 —— ES dense_vector 的维度在建索引时由 {@code kb.embedding.dims} 定死，
 * 改维必须重建索引，属于部署期动作，不受 DB 配置切换影响。解析器只负责凭据与端点。</p>
 */
@Component
public class ModelCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelCredentialResolver.class);

    /** 解析后的运行期凭据（明文，仅内存，绝不落日志/前端）。 */
    public record Credential(String provider, String baseUrl, String model, String apiKey,
                             Map<String, Object> extra) {
        public String extraStr(String key, String def) {
            Object v = extra.get(key);
            return v == null ? def : String.valueOf(v);
        }
    }

    private final ModelConfigMapper mapper;
    private final CryptoService crypto;
    private final ObjectMapper objectMapper;

    public ModelCredentialResolver(ModelConfigMapper mapper, CryptoService crypto,
                                   ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析指定类型的生效凭据：只认 DB 里该类型 is_active=1 的配置行。
     *
     * @param modelType chat|embedding|rerank|tts
     * @return 凭据；该类型无生效配置行时返回 null（调用方据此降级或提示"请在【模型配置】页录入"）
     */
    public Credential resolve(String modelType) {
        ModelConfig cfg = mapper.selectOne(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getModelType, modelType)
                .eq(ModelConfig::getActive, 1));
        if (cfg == null) {
            // debug 而非 warn：resolve 在每次调用（如每批向量化）时都会触发，未配置时上层会立即抛
            // 明确异常，日志里已有那条异常，warn 级别会刷屏。
            log.debug("[AI] 模型类型 {} 无生效配置行（请在【模型配置】页录入并启用）", modelType);
            return null;
        }
        return new Credential(
                cfg.getProvider(),
                cfg.getBaseUrl(),
                cfg.getModel(),
                crypto.decrypt(cfg.getApiKey()),
                parseExtra(cfg.getExtraJson()));
    }

    private Map<String, Object> parseExtra(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) {
            return Map.of();
        }
        try {
            String plain = crypto.decrypt(extraJson);
            return objectMapper.readValue(plain, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[AI] extra_json 解析失败（忽略）: {}", e.getMessage());
            return Map.of();
        }
    }
}
