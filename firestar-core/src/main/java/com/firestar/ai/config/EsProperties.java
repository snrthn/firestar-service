package com.firestar.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 连接 + 索引配置（前缀 kb.es.*，见 application.properties）。
 *
 * <p>P1#5：ES 只做向量 kNN 召回（关键字仍走 MySQL ngram），故连接失败时检索层可优雅降级，
 * 不应因 ES 不可用导致知识库整体不可用。本类只承载配置；ES 连接 Bean 与索引由
 * {@code kb.retriever=hybrid} 条件装配，默认 keyword 模式不触碰 ES。</p>
 */
@Component
@ConfigurationProperties(prefix = "kb.es")
public class EsProperties {

    private static final Logger log = LoggerFactory.getLogger(EsProperties.class);

    private String host = "127.0.0.1";
    private int port = 9200;
    private String scheme = "http";
    /** 向量索引名（dense_vector，dims 与 kb.embedding.dims 对齐）。 */
    private String index = "firestar_kb_vector";
    /** kNN 每路召回候选数。 */
    private int candidates = 50;
    /** 连接超时秒数。 */
    private int connectTimeoutSeconds = 3;
    /**  socket 超时秒数。 */
    private int socketTimeoutSeconds = 5;

    public void validate() {
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("[KB] kb.es.port 非法: " + port);
        }
        if (candidates <= 0) {
            throw new IllegalStateException("[KB] kb.es.candidates 必须 > 0，当前 =" + candidates);
        }
        log.info("[KB] ES 配置: {}:{}/{} index={} candidates={}", scheme, host, port, index, candidates);
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getScheme() { return scheme; }
    public void setScheme(String scheme) { this.scheme = scheme; }
    public String getIndex() { return index; }
    public void setIndex(String index) { this.index = index; }
    public int getCandidates() { return candidates; }
    public void setCandidates(int candidates) { this.candidates = candidates; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
    public int getSocketTimeoutSeconds() { return socketTimeoutSeconds; }
    public void setSocketTimeoutSeconds(int socketTimeoutSeconds) { this.socketTimeoutSeconds = socketTimeoutSeconds; }
}
