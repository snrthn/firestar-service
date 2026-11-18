package com.firestar.ai.kb;

import com.firestar.ai.entity.KbDoc;

/**
 * 向量化出域准入（P1#5 出域闸）：判断一个文档的切片正文能否送第三方 embedding。
 *
 * <p><b>与 {@link KbEgressPolicy} 的区别</b>：
 * <ul>
 *   <li>{@code KbEgressPolicy}：检索结果<b>回模型</b>侧的内容级准入（命中→模型）；</li>
 *   <li>{@code KbVectorEgressPolicy}：文档<b>送第三方 embedding</b>侧的准入（正文→dashscope/Ollama）。
 *       这一侧风险更高——云 embedding 会把正文原文发出去，ACL 只挡召回、不挡向量化。</li>
 * </ul>
 * 两者独立配置、互不影响。链路：{@code KbVectorizeListener.vectorizeDoc → allow → embed}。</p>
 */
public interface KbVectorEgressPolicy {

    /**
     * 文档是否允许向量化（正文出域）。
     *
     * @param doc 文档（读 egressAllowed / aclRoles）
     * @return true = 允许送 embedding；false = 跳过向量化、不进 ES
     */
    boolean canEmbed(KbDoc doc);

    /** 策略标识，便于日志观测当前生效实现。 */
    String id();
}
