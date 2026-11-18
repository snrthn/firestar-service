package com.firestar.ai.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RrfFusionTest {

    @Test
    @DisplayName("1. 双路命中同一文档：分数叠加，排序正确")
    void bothListsHitSameDoc() {
        // 路 A：doc1 rank1, doc2 rank2；路 B：doc2 rank1, doc1 rank2
        List<RrfFusion.Candidate> a = List.of(
                new RrfFusion.Candidate(1, 1, 1.0),
                new RrfFusion.Candidate(2, 2, 1.0));
        List<RrfFusion.Candidate> b = List.of(
                new RrfFusion.Candidate(2, 1, 1.0),
                new RrfFusion.Candidate(1, 2, 1.0));

        Map<Long, Double> fused = RrfFusion.fuse(List.of(a, b));

        assertEquals(List.of(1L, 2L), new java.util.ArrayList<>(fused.keySet()));
        // doc1 = 1/61 + 1/62, doc2 = 1/62 + 1/61 —— 完全相等，按 docId 稳定排序
        assertEquals(fused.get(1L), fused.get(2L), 1e-9);
        assertTrue(fused.get(1L) > 0);
    }

    @Test
    @DisplayName("2. 单路命中 Top1 分数 = 1/(60+1)")
    void singleListTop1() {
        List<RrfFusion.Candidate> a = List.of(
                new RrfFusion.Candidate(9, 1, 1.0),
                new RrfFusion.Candidate(8, 2, 1.0));
        Map<Long, Double> fused = RrfFusion.fuse(List.of(a));
        assertEquals(1.0 / 61, fused.get(9L), 1e-9);
        assertEquals(1.0 / 62, fused.get(8L), 1e-9);
    }

    @Test
    @DisplayName("3. 权重生效：向量路权重 2.0 时其独有 doc 排名反超")
    void weightTakesEffect() {
        List<RrfFusion.Candidate> kw = List.of(new RrfFusion.Candidate(1, 1, 1.0));
        List<RrfFusion.Candidate> vec = List.of(new RrfFusion.Candidate(2, 1, 2.0));
        Map<Long, Double> fused = RrfFusion.fuse(List.of(kw, vec));
        assertTrue(fused.get(2L) > fused.get(1L), "权重 2.0 应让 doc2 分数超过 doc1");
        assertEquals(new java.util.ArrayList<>(fused.keySet()).get(0), 2L);
    }

    @Test
    @DisplayName("4. 空路 / null 路被忽略，rank<=0 被跳过")
    void ignoresEmptyAndInvalid() {
        List<RrfFusion.Candidate> a = List.of(
                new RrfFusion.Candidate(1, 1, 1.0),
                new RrfFusion.Candidate(2, 0, 1.0)); // rank=0 丢弃
        Map<Long, Double> fused = RrfFusion.fuse(java.util.Arrays.asList(a, null, List.of()));
        assertEquals(1, fused.size());
        assertTrue(fused.containsKey(1L));
        assertFalse(fused.containsKey(2L));
    }

    @Test
    @DisplayName("5. 自定义 k 生效")
    void customK() {
        List<RrfFusion.Candidate> a = List.of(new RrfFusion.Candidate(1, 1, 1.0));
        assertEquals(1.0 / 11, RrfFusion.fuse(10, List.of(a)).get(1L), 1e-9);
    }
}
