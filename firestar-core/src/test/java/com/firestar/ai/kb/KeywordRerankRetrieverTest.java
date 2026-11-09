package com.firestar.ai.kb;

import com.firestar.entity.HelpArticle;
import com.firestar.ai.entity.KbChunk;
import com.firestar.ai.entity.KbDoc;
import com.firestar.mapper.HelpArticleMapper;
import com.firestar.ai.mapper.KbChunkMapper;
import com.firestar.ai.mapper.KbDocMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("KeywordRerankRetriever：ACL 过滤 + 重排（纯逻辑，mock mapper）")
class KeywordRerankRetrieverTest {

    private final KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
    private final HelpArticleMapper helpMapper = mock(HelpArticleMapper.class);
    private final KbDocMapper docMapper = mock(KbDocMapper.class);
    private final KeywordRerankRetriever retriever =
            new KeywordRerankRetriever(chunkMapper, helpMapper, docMapper);

    @BeforeEach
    void stubDocList() {
        when(docMapper.selectList(any())).thenReturn(List.of());
    }

    private KbChunk chunk(long docId, String content) {
        KbChunk c = new KbChunk();
        c.setDocId(docId);
        c.setVersion(2);
        c.setHeadingPath("根 > 章节");
        c.setAnchor("sec");
        c.setContent(content);
        c.setSourceRef("kb:doc/" + docId + "/v2/sec");
        c.setStatus(1);
        return c;
    }

    private KbDoc doc(long id, String aclRoles) {
        KbDoc d = new KbDoc();
        d.setId(id);
        d.setAclRoles(aclRoles);
        d.setTitle("文档" + id);
        return d;
    }

    @Test
    @DisplayName("非管理员无权限文档被 ACL 过滤，公开文档保留")
    void aclFiltersHiddenDoc() {
        KbDoc financeDoc = doc(1L, "ROLE_FINANCE");
        KbDoc publicDoc = doc(2L, null);
        when(docMapper.selectList(any())).thenReturn(List.of(financeDoc, publicDoc));
        when(chunkMapper.fulltextSearch(anyString(), anyLong()))
                .thenReturn(List.of(chunk(1L, "报销"), chunk(2L, "报销")));
        when(helpMapper.selectEnabled()).thenReturn(List.of());

        KbSearchContext ctx = new KbSearchContext(false, Set.of("ROLE_HR"), 0L);
        List<KbChunkHit> hits = retriever.retrieve("报销", ctx, 4);

        assertEquals(1, hits.size(), "只有公开文档应被召回");
        assertEquals(2L, hits.get(0).docId());
    }

    @Test
    @DisplayName("管理员无视 ACL，全部召回")
    void adminSeesAll() {
        when(docMapper.selectList(any())).thenReturn(List.of(doc(1L, "ROLE_FINANCE"), doc(2L, null)));
        when(chunkMapper.fulltextSearch(anyString(), anyLong()))
                .thenReturn(List.of(chunk(1L, "报销"), chunk(2L, "报销")));
        when(helpMapper.selectEnabled()).thenReturn(List.of());

        KbSearchContext ctx = new KbSearchContext(true, Set.of(), 0L);
        List<KbChunkHit> hits = retriever.retrieve("报销", ctx, 4);
        assertEquals(2, hits.size());
    }

    @Test
    @DisplayName("标题命中权重高于仅正文命中，排在前面")
    void titleHitRanksAboveContentOnly() {
        KbDoc titleDoc = doc(1L, null);
        titleDoc.setTitle("报销制度");
        KbDoc contentDoc = doc(2L, null);
        contentDoc.setTitle("其他主题");
        when(docMapper.selectList(any())).thenReturn(List.of(titleDoc, contentDoc));
        // 两文档都被 FULLTEXT 召回；titleDoc 仅在标题命中（正文不含关键词），
        // contentDoc 正文提及 3 次（中等相关）。标题信号 +5 应压过正文 +3。
        when(chunkMapper.fulltextSearch(anyString(), anyLong()))
                .thenReturn(List.of(
                        chunk(1L, "这是一篇制度简介"),
                        chunk(2L, "报销 报销 报销")));
        when(helpMapper.selectEnabled()).thenReturn(List.of());

        KbSearchContext ctx = new KbSearchContext(false, Set.of(), 0L);
        List<KbChunkHit> hits = retriever.retrieve("报销", ctx, 4);
        assertEquals(2, hits.size());
        assertEquals(1L, hits.get(0).docId(), "标题含关键词的文档应排第一");
    }

    @Test
    @DisplayName("帮助中心文章命中并标记为 HELP_ARTICLE（负 docId + kb:help 出处）")
    void helpArticlesIncluded() {
        when(chunkMapper.fulltextSearch(anyString(), anyLong())).thenReturn(List.of());
        HelpArticle a = new HelpArticle();
        a.setId(7L);
        a.setTitle("忘记密码怎么办");
        a.setContent("<p>请联系系统管理员重置密码，重置后请妥善保管</p>");
        when(helpMapper.selectEnabled()).thenReturn(List.of(a));

        KbSearchContext ctx = new KbSearchContext(false, Set.of(), 0L);
        List<KbChunkHit> hits = retriever.retrieve("密码", ctx, 4);
        assertFalse(hits.isEmpty());
        KbChunkHit h = hits.get(0);
        assertEquals("HELP_ARTICLE", h.sourceType());
        assertEquals(-7L, h.docId());
        assertTrue(h.sourceRef().startsWith("kb:help/7/v1/"));
    }

    @Test
    @DisplayName("topK 截断")
    void topKLimit() {
        when(chunkMapper.fulltextSearch(anyString(), anyLong())).thenReturn(List.of());
        when(helpMapper.selectEnabled()).thenReturn(List.of(
                help(1L, "密码相关一"), help(2L, "密码相关二"),
                help(3L, "密码相关三"), help(4L, "密码相关四"),
                help(5L, "密码相关五")));
        KbSearchContext ctx = new KbSearchContext(false, Set.of(), 0L);
        List<KbChunkHit> hits = retriever.retrieve("密码", ctx, 2);
        assertEquals(2, hits.size());
    }

    @Test
    @DisplayName("空查询返回空")
    void emptyQuery() {
        when(chunkMapper.fulltextSearch(anyString(), anyLong())).thenReturn(List.of());
        when(helpMapper.selectEnabled()).thenReturn(List.of());
        assertTrue(retriever.retrieve("   ", new KbSearchContext(false, Set.of(), 0L), 4).isEmpty());
    }

    private HelpArticle help(long id, String title) {
        HelpArticle a = new HelpArticle();
        a.setId(id);
        a.setTitle(title);
        a.setContent("<p>" + title + "的内容说明</p>");
        return a;
    }
}
