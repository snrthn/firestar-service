package com.firestar.ai.kb;

/**
 * 检索命中（与 {@link com.firestar.ai.entity.KbChunk} 解耦的轻量结果载体）。
 *
 * <p>MD 文档切片（来自 sys_kb_chunk）与帮助中心文章切片（{@code HelpArticleSource} 即时生成）
 * 统一成同一结构，便于 {@code KeywordRerankRetriever} 合并重排、{@code search_knowledge} 工具统一出口。</p>
 *
 * @param docId       文档 id（帮助文章用负 id 区分）
 * @param version     文档版本（帮助文章固定 1）
 * @param sourceType  MD | HELP_ARTICLE（区分来源，出域/审计用）
 * @param title       文档标题（H1）
 * @param headingPath 标题路径（来源章节，如「公司报销制度 > 住宿标准」）
 * @param anchor      标题 anchor（出处跳转）
 * @param content     切片正文（MD 文本）
 * @param sourceRef   出处串（{@code kb:doc/<id>/v<ver>/<anchor>} 或 {@code kb:help/<id>/v1/<anchor>}）
 * @param score      重排得分（越大越相关）
 */
public record KbChunkHit(Long docId, int version, String sourceType, String title,
                         String headingPath, String anchor, String content,
                         String sourceRef, double score) {
}
