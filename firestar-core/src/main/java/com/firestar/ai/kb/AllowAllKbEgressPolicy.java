package com.firestar.ai.kb;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * P0 默认出域策略：全部放行（步骤 5，设计 §5 / §7）。
 *
 * <p>知识库内容本就是面向用户公开的知识，P0 不额外拦截，与现有 {@code EgressMasker} 链路保持一致。
 * 作为 {@link KbEgressPolicy} 的唯一实现注入，步骤 6 可替换为「按 {@code KbDoc.egressAllowed}
 * 拦截机密文档」的策略而不动调用方。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.egress.policy", havingValue = "allow-all", matchIfMissing = true)
public class AllowAllKbEgressPolicy implements KbEgressPolicy {

    @Override
    public boolean allow(KbChunkHit hit, KbSearchContext ctx) {
        return true;
    }
}
