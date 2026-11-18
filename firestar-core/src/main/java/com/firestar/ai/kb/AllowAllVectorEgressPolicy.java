package com.firestar.ai.kb;

import com.firestar.ai.config.KbVectorEgressProperties;
import com.firestar.ai.entity.KbDoc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 全放行向量出域策略（kb.vectorize.egress.policy=allow-all）。
 *
 * <p>仅用于已确认无敏感内容、或只用本地 embedding（不出域）的场景。
 * 与 {@link SensitiveAwareVectorEgressPolicy} 条件互斥，容器里只有一个 Bean。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.vectorize.egress.policy", havingValue = "allow-all")
public class AllowAllVectorEgressPolicy implements KbVectorEgressPolicy {

    @Override
    public boolean canEmbed(KbDoc doc) {
        return true;
    }

    @Override
    public String id() {
        return KbVectorEgressProperties.POLICY_ALLOW_ALL;
    }
}
