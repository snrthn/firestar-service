package com.firestar.ai.kb;

import com.firestar.ai.entity.KbDoc;

/**
 * 知识库发布门（设计 §7.3 扩展点，配置 kb.publish.gate=noop|flowable）。
 *
 * <p>发布前给一个拦截点，决定文档能否进入 PUBLISHED。默认 Noop 直接放行；
 * 后期 FlowableKbPublishGate 可发流程实例、回调后再置状态，届时不动 KbDocService 发布主流程、
 * 不动表结构（PENDING_REVIEW 状态位与 Flowable 表均已预留）。</p>
 */
public interface KbPublishGate {

    /**
     * 发布前决策。
     *
     * @param doc 待发布文档
     * @return 决策；allowed=true 继续发布，false 由调用方中止
     */
    PublishDecision beforePublish(KbDoc doc);
}
