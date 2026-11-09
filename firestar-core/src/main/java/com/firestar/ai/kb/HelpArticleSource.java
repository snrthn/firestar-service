package com.firestar.ai.kb;

import com.firestar.entity.HelpArticle;
import com.firestar.ai.entity.KbChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 帮助中心文章源（设计 §5 零风险约束）：<b>只读</b>消费 {@code sys_help_article}（status=ENABLED），
 * 把富文本 HTML 清洗为 Markdown 后即时切片，<b>不写原表、不改原接口</b>。
 *
 * <p>为什么单独拆一层：帮助文章是「存量富文本」，与知识库 MD 文档走不同的存储，
 * 但检索时希望二者合并召回。本类把 HTML→MD、再借 {@link KbMarkdownSplitter} 产出与
 * MD 文档同构的 {@link KbChunkHit}，统一进 {@code KeywordRerankRetriever} 的重排流水线。</p>
 *
 * <p>HTML→MD 为纯正则实现（不引第三方库），覆盖 Tiptap 富文本常见标签；
 * 每个切片前置唯一 H1（文章标题），满足 {@code KbMarkdownSplitter} 的「单文档唯一 H1」前提。</p>
 */
public final class HelpArticleSource {

    private HelpArticleSource() {
    }

    /** HTML（Tiptap 富文本）→ Markdown。纯函数，无外部依赖。 */
    public static String htmlToMarkdown(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = html;
        // 去 script / style 块（含内容）
        s = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1>").matcher(s).replaceAll("");
        // 标题：h1/h2/h3 → #/##/###
        s = replaceHeading(s, "h1", "#");
        s = replaceHeading(s, "h2", "##");
        s = replaceHeading(s, "h3", "###");
        // 列表项
        s = s.replaceAll("(?i)<li\\b[^>]*>", "- ");
        // 加粗 / 斜体
        s = s.replaceAll("(?i)<(strong|b)\\b[^>]*>(.*?)</\\1>", "**$2**");
        s = s.replaceAll("(?i)<(em|i)\\b[^>]*>(.*?)</\\1>", "*$2*");
        // 链接 [text](href)
        s = s.replaceAll("(?i)<a\\b[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", "[$2]($1)");
        // 图片：保留 alt 文本（对 RAG 有意义），无 alt 整段丢弃
        s = s.replaceAll("(?i)<img\\b[^>]*\\salt=\"([^\"]*)\"[^>]*/?>", "$1");
        s = s.replaceAll("(?i)<img\\b[^>]*/?>", "");
        // 块级标签 → 换行（先关标签后自闭合，避免重复换行）
        s = s.replaceAll("(?i)</(p|div|blockquote|table|tr)>", "\n");
        s = s.replaceAll("(?i)<(br|hr)\\b[^>]*/?>", "\n");
        // 去除其余残留标签
        s = s.replaceAll("(?i)<[^>]+>", "");
        // HTML 实体反转义
        s = unescape(s);
        // 空白规范化：连续空白压成单空格，连续换行压成最多两个
        s = s.replaceAll("[ \\t  ]+", " ");
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }

    private static String replaceHeading(String s, String tag, String mark) {
        Matcher m = Pattern.compile("(?i)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">")
                .matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(mark + " " + m.group(1).trim()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String unescape(String s) {
        return s
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&copy;", "©")
                .replace("&reg;", "®");
    }

    /**
     * 把启用状态的帮助文章即时切片为 {@link KbChunkHit}。
     *
     * <p>每篇前置唯一 H1（文章标题）后走 {@link KbMarkdownSplitter}；
     * {@code docId} 用文章 id 取负区分 MD 文档，{@code sourceRef} 用
     * {@code kb:help/<id>/v1/<anchor>}，{@code sourceType=HELP_ARTICLE}。
     * score 先置 0，由 {@code KeywordRerankRetriever} 统一重排时重算。</p>
     */
    public static List<KbChunkHit> buildChunks(List<HelpArticle> articles) {
        List<KbChunkHit> hits = new ArrayList<>();
        if (articles == null) {
            return hits;
        }
        for (HelpArticle a : articles) {
            if (a == null || a.getId() == null) {
                continue;
            }
            String title = (a.getTitle() == null || a.getTitle().isBlank()) ? "帮助文档" : a.getTitle();
            String md = "# " + title + "\n\n" + htmlToMarkdown(a.getContent());
            List<KbChunk> chunks = KbMarkdownSplitter.splitToChunks(-a.getId(), 1, md);
            for (KbChunk c : chunks) {
                hits.add(new KbChunkHit(
                        -a.getId(), 1, "HELP_ARTICLE", title,
                        c.getHeadingPath(), c.getAnchor(), c.getContent(),
                        "kb:help/" + a.getId() + "/v1/" + c.getAnchor(),
                        0.0, 0L));
            }
        }
        return hits;
    }
}
