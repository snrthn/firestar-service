package com.firestar.ai.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库三个扩展点的配置绑定（前缀 kb.*，见 application*.properties）。
 *
 * <p>设计契约见 docs/知识库模块设计.md §7：检索器 / 重排 / 出域 / 发布门四个扩展点
 * 一律「接口 + 默认实现 + 配置开关」，切换只改配置 + Bean，不动 ToolExecutor / prompt / 表结构。
 * 本类只承载开关值与启动期合法性校验；具体Bean由各实现类上的 {@code @ConditionalOnProperty} 装配。</p>
 *
 * <p><b>为什么 fail-fast</b>：vector / coze / llm / sensitive-aware / local-only / flowable 等
 * 目标实现尚未落地，若配了这些值而条件装配找不到对应 Bean，容器会在更深的地方报
 * NoSuchBeanDefinitionException（难定位）；不如启动时直接校验并列出当前可用值，
 * 避免「以为切了其实没切」的静默失效。</p>
 */
@Component
@ConfigurationProperties(prefix = "kb")
public class KbProperties {

    private static final Logger log = LoggerFactory.getLogger(KbProperties.class);

    public static final String RETRIEVER_KEYWORD = "keyword";
    public static final String RETRIEVER_HYBRID = "hybrid";
    public static final String RERANK_RULE = "rule";
    public static final String RERANK_MODEL = "model";
    public static final String EGRESS_ALLOW_ALL = "allow-all";
    public static final String PUBLISH_NOOP = "noop";

    /** 当前各开关已落地、可安全配置的值 → 中文说明（用于启动报错提示）。 */
    private static final Map<String, Map<String, String>> SUPPORTED = new LinkedHashMap<>();

    static {
        SUPPORTED.put("kb.retriever", values(
                RETRIEVER_KEYWORD + "=关键词+FULLTEXT检索",
                RETRIEVER_HYBRID + "=关键词+ES向量混合检索（需配 kb.embedding.provider≠none）"));
        SUPPORTED.put("kb.retriever.rerank", values(
                RERANK_RULE + "=规则重排",
                RERANK_MODEL + "=模型语义重排（需配 rerank 类型模型配置，未配置自动降级规则）"));
        SUPPORTED.put("kb.egress.policy", values(EGRESS_ALLOW_ALL + "=全量放行（当前唯一实现）"));
        SUPPORTED.put("kb.publish.gate", values(PUBLISH_NOOP + "=直接发布（当前唯一实现）"));
    }

    private static Map<String, String> values(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : pairs) {
            int i = p.indexOf('=');
            m.put(p.substring(0, i), p.substring(i + 1));
        }
        return m;
    }

    private String retriever = RETRIEVER_KEYWORD;
    private String rerank = RERANK_RULE;
    private String egressPolicy = EGRESS_ALLOW_ALL;
    private String publishGate = PUBLISH_NOOP;

    /** 可选注入：Spring 环境才有，纯单测 new 时为 null，联动校验跳过。 */
    @Autowired(required = false)
    private EmbeddingProperties embeddingProperties;

    /**
     * 启动期 fail-fast 校验：任一开关配了尚未实现的值，直接抛异常终止启动，
     * 错误信息列出该开关当前支持的值。默认值为唯一实现，不配置也可正常启动。
     */
    @PostConstruct
    void validate() {
        check("kb.retriever", retriever);
        check("kb.retriever.rerank", rerank);
        check("kb.egress.policy", egressPolicy);
        check("kb.publish.gate", publishGate);
        // 联动校验：hybrid 必须有 embedding 实现，否则 EsHybridRetriever 注入 EmbeddingProvider 失败
        if (RETRIEVER_HYBRID.equals(retriever) && embeddingProperties != null
                && !embeddingProperties.isEnabled()) {
            throw new IllegalStateException("[KB] kb.retriever=hybrid 但 kb.embedding.provider="
                    + embeddingProperties.getProvider() + "，混合检索需要 local/cloud embedding，"
                    + "请配置 provider 或改回 keyword。");
        }
        log.info("[KB] 扩展点配置 OK: retriever={}, rerank={}, egress.policy={}, publish.gate={}",
                retriever, rerank, egressPolicy, publishGate);
    }

    private void check(String key, String value) {
        Map<String, String> supported = SUPPORTED.get(key);
        if (value == null || !supported.containsKey(value)) {
            throw new IllegalStateException("[KB] " + key + "='" + value
                    + "' 尚无实现，当前可选值：" + supported
                    + "。切换前请先落地对应 Bean，或改回默认值。");
        }
    }

    public String getRetriever() {
        return retriever;
    }

    public void setRetriever(String retriever) {
        this.retriever = retriever;
    }

    public String getRerank() {
        return rerank;
    }

    public void setRerank(String rerank) {
        this.rerank = rerank;
    }

    public String getEgressPolicy() {
        return egressPolicy;
    }

    public void setEgressPolicy(String egressPolicy) {
        this.egressPolicy = egressPolicy;
    }

    public String getPublishGate() {
        return publishGate;
    }

    public void setPublishGate(String publishGate) {
        this.publishGate = publishGate;
    }
}
