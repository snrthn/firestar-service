package com.firestar.ai.kb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合纯函数（P1#5，Java 端融合，无需 ES 付费许可证）。
 *
 * <p>公式：{@code score(d) = Σ_over_lists 1 / (k + rank_l(d))}，k=60（业界默认值）。
 * <b>只用排名、不用原始分</b>——这是 RRF 鲁棒性的来源：关键字路是规则重排分、向量路是
 * 余弦相似度，量纲完全不同，无法直接加权；RRF 把它们各自转成排名再融合，规避了归一化难题。</p>
 *
 * <p>与 ES 原生 RRF 数学等价（实测探针 EsCompatibilityProbeTest 验证 Top1 一致），
 * 但 Basic 许可证即可用，且可给不同路设权重、完全可单测。</p>
 */
public final class RrfFusion {

    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /** 带权重的候选条目：docId + 该路排名（从 1 起）+ 该路权重。 */
    public record Candidate(long docId, int rank, double weight) {
    }

    /**
     * 融合多路候选，返回按 RRF 分降序的 docId→score（LinkedHashMap 保序）。
     *
     * @param k        平滑常数，通常 60
     * @param lists    每一路已按相关度降序排好的候选（rank 由列表下标决定，从 1 起）
     * @return docId → 融合分，降序
     */
    public static Map<Long, Double> fuse(int k, List<List<Candidate>> lists) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (List<Candidate> list : lists) {
            if (list == null) {
                continue;
            }
            for (Candidate c : list) {
                if (c == null || c.rank() <= 0) {
                    continue;
                }
                double contribution = c.weight() / (k + (double) c.rank());
                scores.merge(c.docId(), contribution, Double::sum);
            }
        }
        List<Map.Entry<Long, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Comparator.<Map.Entry<Long, Double>>comparingDouble(Map.Entry::getValue).reversed()
                .thenComparingLong(Map.Entry::getKey));
        Map<Long, Double> out = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> e : sorted) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 默认 k=60、各路权重 1.0 的便捷入口。 */
    public static Map<Long, Double> fuse(List<List<Candidate>> lists) {
        return fuse(DEFAULT_K, lists);
    }
}
