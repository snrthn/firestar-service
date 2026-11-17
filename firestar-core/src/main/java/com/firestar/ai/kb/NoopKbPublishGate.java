package com.firestar.ai.kb;

import com.firestar.ai.entity.KbDoc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认发布门（kb.publish.gate=noop）：直接放行，与现状行为完全一致（P0 点发布即生效）。
 *
 * <p>作为 {@link KbPublishGate} 的唯一实现注入。后期切 flowable 时新增实现类 + 改配置即可，
 * 发布主流程 KbDocService.publish() 不动。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.publish.gate", havingValue = "noop", matchIfMissing = true)
public class NoopKbPublishGate implements KbPublishGate {

    @Override
    public PublishDecision beforePublish(KbDoc doc) {
        return PublishDecision.allow();
    }
}
