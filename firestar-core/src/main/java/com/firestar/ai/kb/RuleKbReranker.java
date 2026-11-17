package com.firestar.ai.kb;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则重排（kb.retriever.rerank=rule 的默认实现，设计 §7.1）。
 *
 * <p>纯函数无外部依赖：标题命中 +5/处，heading 命中 +3/处，正文命中 +1/处（每 term 封顶 10）。
 * 从 KeywordRerankRetriever 的原 static score() 原样迁移，行为不变。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.retriever.rerank", havingValue = "rule", matchIfMissing = true)
public class RuleKbReranker implements KbReranker {

    @Override
    public double score(KbChunkHit h, List<String> terms) {
        String title = (h.title() == null ? "" : h.title()).toLowerCase();
        String heading = (h.headingPath() == null ? "" : h.headingPath()).toLowerCase();
        String content = (h.content() == null ? "" : h.content()).toLowerCase();
        double s = 0;
        for (String t : terms) {
            if (title.contains(t)) {
                s += 5;
            }
            if (heading.contains(t)) {
                s += 3;
            }
            s += Math.min(countOccurrences(content, t), 10) * 1.0;
        }
        return s;
    }

    private static int countOccurrences(String text, String sub) {
        if (text.isEmpty() || sub.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
