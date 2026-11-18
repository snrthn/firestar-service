package com.firestar.ai.kb;

import com.firestar.ai.rerank.RerankProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V214：模型重排单测 —— 一次批量调用、文档文本拼接、异常降级为规则分。 */
class ModelKbRerankerTest {

    private KbChunkHit hit(long docId, String title, String heading, String content) {
        return new KbChunkHit(docId, 1, "MD", title, heading, "a", content,
                "kb:doc/" + docId + "/v1/a", 0.0, 0L);
    }

    @Test
    @DisplayName("正常：一次批量调用，分数按序返回，文档文本含标题+heading+正文")
    void batchRerank() {
        RerankProvider provider = mock(RerankProvider.class);
        when(provider.rerank(anyString(), anyList())).thenReturn(List.of(0.9, 0.1));
        ModelKbReranker reranker = new ModelKbReranker(provider);

        List<KbChunkHit> hits = List.of(
                hit(1L, "报销制度", "差旅 > 住宿", "住宿标准 500 元"),
                hit(2L, "考勤", "打卡", "迟到扣款"));
        List<Double> scores = reranker.scoreBatch(hits, List.of("报销", "住宿"));

        assertEquals(List.of(0.9, 0.1), scores);
        verify(provider, times(1)).rerank(anyString(), anyList()); // 一次 HTTP，不是每条一次
        org.mockito.ArgumentCaptor<List<String>> cap = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(provider).rerank(eq("报销 住宿"), cap.capture());
        assertTrue(cap.getValue().get(0).contains("报销制度"));
        assertTrue(cap.getValue().get(0).contains("住宿标准 500 元"));
    }

    @Test
    @DisplayName("降级：provider 抛异常时回退规则分，不再抛错")
    void fallbackOnError() {
        RerankProvider provider = mock(RerankProvider.class);
        when(provider.rerank(anyString(), anyList())).thenThrow(new RuntimeException("模型不可用"));
        ModelKbReranker reranker = new ModelKbReranker(provider);

        KbChunkHit h = hit(1L, "报销制度", "", "报销 住宿 住宿");
        List<Double> scores = reranker.scoreBatch(List.of(h), List.of("报销", "住宿"));
        assertNotNull(scores);
        assertEquals(1, scores.size());
        assertTrue(scores.get(0) > 0, "规则降级应给出正分：标题命中+5");
    }

    @Test
    @DisplayName("空命中返回空列表，不调用 provider")
    void emptyHits() {
        RerankProvider provider = mock(RerankProvider.class);
        ModelKbReranker reranker = new ModelKbReranker(provider);
        assertTrue(reranker.scoreBatch(List.of(), List.of("x")).isEmpty());
        verify(provider, never()).rerank(anyString(), anyList());
    }
}
