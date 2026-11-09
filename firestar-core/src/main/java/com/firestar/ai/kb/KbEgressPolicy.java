package com.firestar.ai.kb;

/**
 * 知识库内容级出域准入（步骤 5 落地，设计 §7 / §5）。
 *
 * <p>与现有 {@code EgressMasker}（字段级脱敏）互补：{@code EgressMasker} 负责「发往供应商前
 * 改写敏感字段」，本接口负责「这条知识命中本身是否允许出域」。链路固定为
 * {@code Retriever → KbEgressPolicy → EgressMasker}。</p>
 *
 * <p>P0：{@link AllowAllKbEgressPolicy} 全部放行（与现有链路一致，知识库内容本就是面向用户公开的知识）。
 * 后续步骤 6 可据 {@code KbDoc.egressAllowed} 实现细粒度拦截（如某些机密文档禁止被 AI 引用）。</p>
 */
public interface KbEgressPolicy {

    /**
     * 判断命中是否允许出域。
     *
     * @param hit 检索命中
     * @param ctx 检索上下文（可据角色/租户做差异策略）
     * @return true = 允许发给供应商 / 模型
     */
    boolean allow(KbChunkHit hit, KbSearchContext ctx);
}
