package com.firestar.ai.kb;

import com.firestar.ai.config.EsProperties;
import com.firestar.ai.embedding.EmbeddingProvider;
import com.firestar.ai.es.EsVectorStore;
import com.firestar.ai.mapper.KbChunkMapper;
import com.firestar.ai.mapper.KbDocMapper;
import com.firestar.mapper.HelpArticleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EsHybridRetriever 降级与融合逻辑单测（不连真实 ES，纯 mock）。
 */
@ExtendWith(MockitoExtension.class)
class EsHybridRetrieverTest {

    @Mock KbDocMapper docMapper;
    @Mock KbChunkMapper chunkMapper;
    @Mock HelpArticleMapper helpMapper;
    @Mock EmbeddingProvider embeddingProvider;
    @Mock EsVectorStore vectorStore;

    private EsHybridRetriever retriever;
    private final KbSearchContext ctx = new KbSearchContext(false, Set.of(), 0L);

    @BeforeEach
    void setUp() {
        EsProperties esProps = new EsProperties();
        retriever = new EsHybridRetriever(docMapper, chunkMapper, helpMapper,
                new RuleKbReranker(), embeddingProvider, vectorStore, esProps);
    }

    @Test
    @DisplayName("1. ES/embedding 抛异常时降级为纯关键字，不崩")
    void degradesToKeywordWhenEsDown() throws IOException {
        // 关键字路：FULLTEXT 无命中、帮助文章无命中 → 空
        when(chunkMapper.fulltextSearch(anyString(), anyLong())).thenReturn(List.of());
        when(helpMapper.selectEnabled()).thenReturn(List.of());
        // 向量路：抛异常
        when(embeddingProvider.embedOne(anyString())).thenThrow(new RuntimeException("ES 不可用"));

        List<KbChunkHit> hits = retriever.retrieve("报销", ctx, 4);

        assertNotNull(hits, "降级后应返回非空列表对象（可能为空集合），绝不为 null");
        assertTrue(hits.isEmpty());
        verify(vectorStore, never()).knnSearch(any(float[].class), anyInt());
    }

    @Test
    @DisplayName("2. knnSearch 抛 IOException 同样降级，不传播异常")
    void degradesWhenKnnThrows() throws IOException {
        when(chunkMapper.fulltextSearch(anyString(), anyLong())).thenReturn(List.of());
        when(helpMapper.selectEnabled()).thenReturn(List.of());
        when(embeddingProvider.embedOne(anyString())).thenReturn(new float[1024]);
        when(vectorStore.knnSearch(any(float[].class), anyInt())).thenThrow(new IOException("ES 连接拒绝"));

        assertDoesNotThrow(() -> retriever.retrieve("报销", ctx, 4));
    }

    @Test
    @DisplayName("3. 空 query 直接走关键字路，不触发向量化")
    void blankQuerySkipsVector() {
        List<KbChunkHit> hits = retriever.retrieve("   ", ctx, 4);
        assertNotNull(hits);
        verifyNoInteractions(embeddingProvider, vectorStore);
    }
}
