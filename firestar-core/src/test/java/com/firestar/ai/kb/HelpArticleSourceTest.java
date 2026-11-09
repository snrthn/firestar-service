package com.firestar.ai.kb;

import com.firestar.entity.HelpArticle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HelpArticleSource：HTML→MD + 即时切片")
class HelpArticleSourceTest {

    @Test
    @DisplayName("htmlToMarkdown：段落/标题/加粗/链接/反转义")
    void htmlToMarkdown() {
        assertEquals("你好", HelpArticleSource.htmlToMarkdown("<p>你好</p>").trim());
        assertEquals("# 标题", HelpArticleSource.htmlToMarkdown("<h1>标题</h1>").trim());
        assertEquals("## 小节", HelpArticleSource.htmlToMarkdown("<h2>小节</h2>").trim());
        assertEquals("**粗体**", HelpArticleSource.htmlToMarkdown("<p><strong>粗体</strong></p>").trim());
        assertEquals("[链接](http://x.com)",
                HelpArticleSource.htmlToMarkdown("<p><a href=\"http://x.com\">链接</a></p>").trim());
        assertEquals("a & b < c",
                HelpArticleSource.htmlToMarkdown("<p>a &amp; b &lt; c</p>").trim());
    }

    @Test
    @DisplayName("htmlToMarkdown：去除 script / style 块")
    void stripsScriptStyle() {
        String html = "<p>正文</p><script>alert(1)</script><style>.x{color:red}</style>";
        String md = HelpArticleSource.htmlToMarkdown(html);
        assertFalse(md.contains("alert"));
        assertFalse(md.contains("color:red"));
        assertTrue(md.contains("正文"));
    }

    @Test
    @DisplayName("buildChunks：负 docId + HELP_ARTICLE + kb:help sourceRef")
    void buildChunks() {
        HelpArticle a = new HelpArticle();
        a.setId(5L);
        a.setTitle("忘记密码怎么办");
        a.setContent("<p>请联系系统管理员重置密码</p>");
        List<KbChunkHit> hits = HelpArticleSource.buildChunks(List.of(a));
        assertFalse(hits.isEmpty(), "至少产出一个切片");
        KbChunkHit h = hits.get(0);
        assertEquals(-5L, h.docId());
        assertEquals(1, h.version());
        assertEquals("HELP_ARTICLE", h.sourceType());
        assertTrue(h.title().contains("忘记密码"));
        assertTrue(h.sourceRef().startsWith("kb:help/5/v1/"),
                "sourceRef 应为 kb:help/<id>/v1/<anchor>，实际=" + h.sourceRef());
    }
}
