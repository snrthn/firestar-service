package com.firestar.ai.controller;

import com.firestar.ai.kb.KbChunkHit;
import com.firestar.ai.kb.KbEgressPolicy;
import com.firestar.ai.kb.KbRetriever;
import com.firestar.ai.kb.KbSearchContext;
import com.firestar.ai.vo.KbRetrieveVO;
import com.firestar.common.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("KbRetrievalDebugController：检索调试端点结果映射 / topK 夹取 / 出域过滤")
class KbRetrievalDebugControllerTest {

    private final KbRetriever retriever = mock(KbRetriever.class);
    private final KbEgressPolicy egress = mock(KbEgressPolicy.class);
    private final KbRetrievalDebugController controller =
            new KbRetrievalDebugController(retriever, egress);

    @BeforeEach
    void auth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private KbChunkHit hit(long docId, String title, double score) {
        return new KbChunkHit(docId, 2, "MD", title, "H1 > H2", "sec",
                "正文片段", "kb:doc/" + docId + "/v2/sec", score, 0L);
    }

    @Test
    @DisplayName("命中明细完整映射：score/title/sourceRef/heading 都进 VO")
    void mapsHitsToVO() {
        when(retriever.retrieve(anyString(), any(KbSearchContext.class), anyInt()))
                .thenReturn(List.of(hit(900001, "飞星报销规范", 7.5)));
        when(egress.allow(any(), any())).thenReturn(true);

        Result<KbRetrieveVO> result = controller.retrieve("飞星报销", 4);
        assertEquals(0, result.getCode());
        KbRetrieveVO vo = result.getData();
        assertEquals("飞星报销", vo.getQuery());
        assertEquals(1, vo.getTotal());
        assertEquals(1, vo.getHits().size());
        var h = vo.getHits().get(0);
        assertEquals(900001L, h.getDocId());
        assertEquals("飞星报销规范", h.getTitle());
        assertEquals("H1 > H2", h.getHeadingPath());
        assertEquals("正文片段", h.getContent());
        assertEquals("kb:doc/900001/v2/sec", h.getSourceRef());
        assertEquals(7.5, h.getScore());
        assertTrue(vo.getElapsedMs() >= 0);
    }

    @Test
    @DisplayName("topK 夹取：负数取默认 4，超上限夹到 8")
    void clampsTopK() {
        when(retriever.retrieve(anyString(), any(KbSearchContext.class), anyInt()))
                .thenReturn(List.of());

        assertEquals(4, controller.retrieve("q", -3).getData().getTopK());
        assertEquals(4, controller.retrieve("q", null).getData().getTopK());
        assertEquals(8, controller.retrieve("q", 999).getData().getTopK());
    }

    @Test
    @DisplayName("出域策略拒绝的命中不进结果")
    void egressFiltersHits() {
        when(retriever.retrieve(anyString(), any(KbSearchContext.class), anyInt()))
                .thenReturn(List.of(hit(1, "允许", 5), hit(2, "拒绝", 9)));
        when(egress.allow(any(), any())).thenAnswer(inv -> {
            KbChunkHit h = inv.getArgument(0);
            return h.docId() == 1L;
        });

        KbRetrieveVO vo = controller.retrieve("q", 4).getData();
        assertEquals(1, vo.getTotal());
        assertEquals(1L, vo.getHits().get(0).getDocId());
    }
}
