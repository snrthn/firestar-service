package com.firestar.ai.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Embedding 配置（前缀 kb.embedding.*，见 application.properties）。
 *
 * <p>P1#5：抽象 EmbeddingProvider 双实现（local / cloud），本类只承载配置与启动期校验。
 * <b>dims 是硬前置</b>：它在建 ES dense_vector 索引时定死，改维度必须重建索引，
 * 故默认 1024（bge-m3 与 dashscope text-embedding-v3 均为此维度），且启动期校验 > 0。</p>
 *
 * <p>provider 三值：{@code none}（默认，未接入时检索降级为纯关键字）、{@code local}（Ollama）、
 * {@code cloud}（dashscope compatible-mode）。配错值 fail-fast，避免静默不生效。</p>
 *
 * <p><b>provider=cloud 时，端点 / 模型名 / 密钥一律在【模型配置】页维护</b>
 * （即 sys_model_config 里 model_type=embedding 的生效行），本类不再承载 cloud 凭据 ——
 * 密钥不再从 properties / 环境变量注入，避免同一把 key 散落两处、谁生效说不清。
 * {@code kb.embedding.dims} 是唯一例外：它在建 ES 索引时定死，属部署期参数，保留在此。</p>
 */
@Component
@ConfigurationProperties(prefix = "kb.embedding")
public class EmbeddingProperties {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProperties.class);

    public static final String PROVIDER_NONE = "none";
    public static final String PROVIDER_LOCAL = "local";
    public static final String PROVIDER_CLOUD = "cloud";

    /** 默认不接入：未配 provider 时系统走纯关键字检索，不依赖任何 embedding 服务。 */
    private String provider = PROVIDER_NONE;
    /** 向量维度，建索引时定死，改维必须重建。 */
    private int dims = 1024;
    /** Ollama embedding 模型名（provider=local 用）。 */
    private String localModel = "bge-m3";
    /** Ollama 服务根地址（provider=local 用）。 */
    private String localBaseUrl = "http://127.0.0.1:11434";
    /** 单次 embedding 文本最大字符数，超长截断（模型 token 上限保护）。 */
    private int maxChars = 2000;
    /** embedding HTTP 连接超时秒数。 */
    private int connectTimeoutSeconds = 3;
    /** embedding HTTP socket 读取超时秒数（云端批量可能较慢）。 */
    private int socketTimeoutSeconds = 15;

    @PostConstruct
    void validate() {
        if (!PROVIDER_NONE.equals(provider) && !PROVIDER_LOCAL.equals(provider)
                && !PROVIDER_CLOUD.equals(provider)) {
            throw new IllegalStateException("[KB] kb.embedding.provider='" + provider
                    + "' 不合法，当前可选值：[" + PROVIDER_NONE + ", " + PROVIDER_LOCAL + ", " + PROVIDER_CLOUD + "]");
        }
        if (dims <= 0) {
            throw new IllegalStateException("[KB] kb.embedding.dims 必须 > 0，当前 =" + dims
                    + "。注意 dims 建索引后不可改，改维需重建 ES 索引。");
        }
        log.info("[KB] embedding 配置 OK: provider={}, dims={}, maxChars={}", provider, dims, maxChars);
    }

    public boolean isEnabled() {
        return !PROVIDER_NONE.equals(provider);
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public int getDims() { return dims; }
    public void setDims(int dims) { this.dims = dims; }
    public String getLocalModel() { return localModel; }
    public void setLocalModel(String localModel) { this.localModel = localModel; }
    public String getLocalBaseUrl() { return localBaseUrl; }
    public void setLocalBaseUrl(String localBaseUrl) { this.localBaseUrl = localBaseUrl; }
    public int getMaxChars() { return maxChars; }
    public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
    public int getSocketTimeoutSeconds() { return socketTimeoutSeconds; }
    public void setSocketTimeoutSeconds(int socketTimeoutSeconds) { this.socketTimeoutSeconds = socketTimeoutSeconds; }
}
