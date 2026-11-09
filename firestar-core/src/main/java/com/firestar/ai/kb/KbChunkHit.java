package com.firestar.ai.kb;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索命中（与 {@link com.firestar.ai.entity.KbChunk} 解耦的轻量结果载体）。
 *
 * <p>MD 文档切片（来自 sys_kb_chunk）与帮助中心文章切片（{@code HelpArticleSource} 即时生成）、
 * REFERENCE 附件切片统一成同一结构，便于 {@code KeywordRerankRetriever} 合并重排、
 * {@code search_knowledge} 工具统一出口。</p>
 *
 * @param docId       文档 id（帮助文章用负 id 区分；附件切片复用归属文档 id，保证同版本召回）
 * @param version     文档版本（帮助文章固定 1）
 * @param sourceType  MD | HELP_ARTICLE（区分来源，出域/审计用）
 * @param title       文档标题（H1）
 * @param headingPath 标题路径（来源章节，如「公司报销制度 > 住宿标准」）
 * @param anchor      标题 anchor（出处跳转）
 * @param content     切片正文（MD 文本）
 * @param sourceRef   出处串（{@code kb:doc/<id>/v<ver>/<anchor>}、
 *                     {@code kb:help/<id>/v1/<anchor>} 或 {@code kb:att/<fileId>/<anchor>}）
 * @param score      重排得分（越大越相关）
 * @param fileId     来源附件 id（仅 REFERENCE 附件切片 > 0；MD/帮助文章恒 0/空）
 */
public record KbChunkHit(Long docId, int version, String sourceType, String title,
                         String headingPath, String anchor, String content,
                         String sourceRef, double score, Long fileId) {

    private static final Pattern ATT_REF = Pattern.compile("^kb:att/(\\d+)/");

    /**
     * 从 sourceRef 反解 REFERENCE 附件 id（{@code kb:att/<fileId>/<anchor>}）。
     *
     * <p>非附件出处（MD 文档 / 帮助文章）或未匹配时返回 0，调用方据此判断是否为附件命中。</p>
     */
    public static long parseFileId(String sourceRef) {
        if (sourceRef == null) {
            return 0L;
        }
        Matcher m = ATT_REF.matcher(sourceRef);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }
}
