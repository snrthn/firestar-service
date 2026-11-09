package com.firestar.ai.kb;

import com.firestar.ai.entity.KbChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KbMarkdownSplitter 纯函数单测（不连库、不启 Spring，对齐设计 §3.3 / §3.4）。 */
class KbMarkdownSplitterTest {

    @Test
    @DisplayName("单 H1 + 短正文 → 1 个 chunk，headingPath 为标题")
    void singleH1() {
        String md = "# 公司报销制度\n\n这是一段简介。\n";
        List<KbChunk> chunks = KbMarkdownSplitter.splitToChunks(1L, 1, md);
        assertEquals(1, chunks.size());
        KbChunk c = chunks.get(0);
        assertEquals("公司报销制度", c.getHeadingPath());
        assertEquals(0, c.getSeq());
        assertTrue(c.getContent().startsWith("# 公司报销制度"));
        assertTrue(c.getContent().contains("这是一段简介"));
        assertEquals("kb:doc/1/v1/" + c.getAnchor(), c.getSourceRef());
        assertEquals(1, c.getStatus());
        assertEquals("NONE", c.getVectorStatus());
    }

    @Test
    @DisplayName("H1 + 两个 H2：各自成块，headingPath 含 H1 祖先")
    void h1WithH2() {
        String md = "# 公司报销制度\n\n简介。\n\n## 住宿标准\n\n住宿每天限额。\n\n## 交通标准\n\n高铁二等座。\n";
        List<KbChunk> chunks = KbMarkdownSplitter.splitToChunks(2L, 1, md);
        // 简介块(从H1 body) + 住宿 + 交通 = 3
        assertEquals(3, chunks.size());
        assertEquals("公司报销制度", chunks.get(0).getHeadingPath());
        assertEquals("公司报销制度 > 住宿标准", chunks.get(1).getHeadingPath());
        assertEquals("公司报销制度 > 交通标准", chunks.get(2).getHeadingPath());
        // seq 连续
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getSeq());
        }
    }

    @Test
    @DisplayName("超大 H2 正文：拆成多块，每块复现标题、overlap 不跨标题、均不超上限")
    void largeSectionSplits() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append("本条财务规定要求员工在报销时提供合规发票并注明用途。\n");
        }
        String md = "# 公司报销制度\n\n简介。\n\n## 报销细则\n\n" + body + "\n";
        List<KbChunk> chunks = KbMarkdownSplitter.splitToChunks(3L, 1, md);
        // chunk[0]=H1封面, 之后都是 H2「报销细则」拆出的块
        assertTrue(chunks.size() > 2, "超长章节应被拆分为多块，实际=" + chunks.size());
        for (int i = 1; i < chunks.size(); i++) {
            KbChunk c = chunks.get(i);
            assertTrue(c.getContent().startsWith("## 报销细则"),
                    "每个 H2 chunk 都应复现所属标题");
            assertTrue(c.getTokenCnt() <= KbMarkdownSplitter.CHUNK_TOKEN_CAP
                            + KbMarkdownSplitter.CHUNK_OVERLAP + 50,
                    "含 overlap 的块不应超过 CAP + OVERLAP，实际=" + c.getTokenCnt());
        }
        // overlap：上一块尾部内容应出现在下一块（作为 prevTail）
        String tail1 = lastSentence(chunks.get(1).getContent());
        assertTrue(chunks.get(2).getContent().contains(tail1),
                "相邻 chunk 之间应有 overlap 上下文");
    }

    @Test
    @DisplayName("表格整块保留：不被按行拆散")
    void tablePreserved() {
        String md = "# 差旅标准\n\n## 城市分级\n\n" +
                "| 城市 | 住宿上限 | 餐补 |\n" +
                "| --- | --- | --- |\n" +
                "| 北京 | 500 | 100 |\n" +
                "| 上海 | 480 | 100 |\n";
        List<KbChunk> chunks = KbMarkdownSplitter.splitToChunks(4L, 1, md);
        boolean hasTable = chunks.stream()
                .anyMatch(c -> c.getContent().contains("| 城市 | 住宿上限")
                        && c.getContent().contains("| 上海 | 480 | 100 |"));
        assertTrue(hasTable, "表格应作为整体保留在一个 chunk 内");
    }

    @Test
    @DisplayName("fenced code 不被当标题拆散")
    void fencedCode() {
        String md = "# 示例\n\n## 代码片段\n\n```\n# 这不是标题\nconst a = 1;\n```\n";
        List<KbChunk> chunks = KbMarkdownSplitter.splitToChunks(5L, 1, md);
        KbChunk c = chunks.get(1);
        assertEquals("示例 > 代码片段", c.getHeadingPath());
        assertTrue(c.getContent().contains("# 这不是标题"),
                "代码块内的 # 不应被识别为标题");
    }

    @Test
    @DisplayName("空 H2 不产生 chunk；仅有 H1 仍生成封面 chunk")
    void emptyH2Skipped() {
        String md = "# 仅标题文档\n\n## 空章节\n\n## 有效章节\n\n有内容。\n";
        List<KbChunk> chunks = KbMarkdownSplitter.splitToChunks(6L, 1, md);
        // H1 封面块 + 有效章节块 = 2（空章节被跳过）
        assertEquals(2, chunks.size());
        assertEquals("仅标题文档", chunks.get(0).getHeadingPath());
        assertEquals("仅标题文档 > 有效章节", chunks.get(1).getHeadingPath());
    }

    private static String lastSentence(String content) {
        String[] parts = content.split("。");
        return parts[parts.length - 1];
    }

    @Test
    @DisplayName("anchor 可读且非空（允许中文，作为可读 slug）")
    void anchor() {
        String md = "# 公司报销制度\n\n内容。\n";
        KbChunk c = KbMarkdownSplitter.splitToChunks(7L, 1, md).get(0);
        assertFalse(c.getAnchor() == null || c.getAnchor().isBlank());
        assertTrue(c.getAnchor().matches("[\\u4e00-\\u9fa5a-z0-9\\-]+"),
                "anchor 应为中文/字母/数字/连字符，实际=" + c.getAnchor());
    }
}
