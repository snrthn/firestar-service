package com.firestar.ai.core;

import java.util.function.Consumer;

/**
 * 扣子 answer 首句「过程独白」过滤器（跨帧安全，仅扣子使用）。
 *
 * <p>背景（2026-11-22 实锤，请求 b870597e / 70d9bb2b）：豆包/扣子深度思考 Bot 有时会把
 * 「我要开始干活了」的元陈述<b>同时</b>写进 reasoning_content 和 answer 的 content 首句，
 * 例如「用户现在需要查询研发中心下面的部门数量，我将直接调用组织架构查询工具完成本次查询。」
 * reasoning 通道保留这句是合理的（用户展开「已深度思考」能看到模型意图），但正文气泡里
 * 再重复一遍就成了用户反感的「自言自语」。</p>
 *
 * <p>本过滤器只作用于 answer token 链路（DeepSeek / OpenAI / Anthropic 不接，零影响）:
 * 缓冲开头直到第一个句末标点形成完整首句，仅当首句<b>同时</b>满足
 * ①以「用户 / 我 / 正在」开头 ②含调用/查询/统计类动作词 ③含「工具」或「正在为你」等过程信号
 * 时才判为独白并丢弃该句；其余文本（含正常长句、引用、代码块）原样透传。</p>
 *
 * <p>流结束 flush 只吐残留、不再判定，避免把模型最后的正常短句误删。
 * 阈值兜底：缓冲超过 {@link #MAX_PREAMBLE_CHARS} 仍无句末标点即按正常正文整段放行。</p>
 */
public final class AnswerPreambleFilter {

    private static final int MAX_PREAMBLE_CHARS = 200;
    /** 句末标点（中文句号/问号/叹号/换行/英文句号） */
    private static final char[] SENTENCE_ENDS = {'。', '？', '！', '?', '!', '\n'};
    /** 过程独白的开头词；其余开头的正文第一个 token 就透传，零延迟。
     *  2026-11-23 生产实测（会话18/请求 f2ac5f45）：端插件模式下独白还会以「正在为你查询…」开头，
     *  且句内不含「工具」二字（如"正在为你统计其名下的项目数量与对应回款总额。"），原词表拦不住。 */
    private static final String[] PREAMBLE_STARTS = {"用户", "我", "正在"};

    private final Consumer<String> onText;
    /** 首句是否已判定；判定后进入透传模式，不再缓冲 */
    private boolean resolved;
    private final StringBuilder pending = new StringBuilder();

    public AnswerPreambleFilter(Consumer<String> onText) {
        this.onText = onText;
    }

    /** 喂入一个 answer token 增量；只发射已确定安全的部分 */
    public void accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        if (resolved) {
            onText.accept(chunk);
            return;
        }
        pending.append(chunk);
        // 关键：只有「仍可能是独白开头」才继续缓冲。一旦确定不是（比如首字是"研"、
        // "请"、"答案"），整段立即透传并进入直通模式——正常回答零延迟，绝不被憋住。
        if (!mightBePreamble(pending)) {
            onText.accept(pending.toString());
            pending.setLength(0);
            resolved = true;
            return;
        }
        int end = firstSentenceEnd(pending);
        if (end < 0 && pending.length() < MAX_PREAMBLE_CHARS) {
            return; // 首句未完整，继续攒
        }
        resolve();
    }

    /** pending 是否仍可能是过程独白的开头（前缀匹配，应对首帧只有半个"用"字） */
    private static boolean mightBePreamble(CharSequence s) {
        if (s.length() == 0) {
            return true;
        }
        for (String start : PREAMBLE_STARTS) {
            int n = Math.min(s.length(), start.length());
            if (start.regionMatches(0, s.toString(), 0, n)) {
                return true;
            }
        }
        return false;
    }

    /** 流结束：把缓冲区残留原样吐出（不再判定，防止误删正常短句） */
    public void finish() {
        if (resolved) {
            return;
        }
        if (pending.length() > 0) {
            onText.accept(pending.toString());
            pending.setLength(0);
        }
        resolved = true;
    }

    /** 判定缓冲中的首句：命中过程独白则丢弃首句，首句之后的文本照常下发 */
    private void resolve() {
        resolved = true;
        String buf = pending.toString();
        int end = firstSentenceEnd(pending);
        // 超长无句末标点：整段当正常正文放行（不判定，宁可漏删不可误删）
        if (end < 0) {
            onText.accept(buf);
            pending.setLength(0);
            return;
        }
        String first = buf.substring(0, end + 1);
        String rest = buf.substring(end + 1);
        if (isProcessPreamble(first)) {
            if (!rest.isEmpty()) {
                onText.accept(rest);
            }
        } else {
            onText.accept(buf);
        }
        pending.setLength(0);
    }

    /**
     * 保守判定「过程独白」，按开头词分组收紧——不同开头的独白形态不同，混用单一规则会误删正常回答
     * （2026-11-23 回归实锤："我调用了组织架构查询工具，因为需要核对部门数据。"是模型的正常回答，
     *  放宽后「我」开头+含工具就命中被误删）。
     * <ul>
     *   <li>「用户 / 我」开头：维持原四条件（复述意图/自述动作 + 动作词 + 提到工具 + 过程元话语），
     *       "我调用了…"这类回答性陈述不含元话语即放行；</li>
     *   <li>「正在」开头：2026-11-23 生产实测（会话18）端插件独白以「正在为你查询/统计…」开头
     *       且句内无「工具」二字——「正在为你/为您」+ 动作词即判独白；正常回答不会以过程播报开头。</li>
     * </ul>
     */
    static boolean isProcessPreamble(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return false;
        }
        String s = sentence.trim();
        // ② 动作词：调用/查询/统计类（两条分支共用）
        boolean hasAction = s.contains("调用") || s.contains("查询") || s.contains("检索")
                || s.contains("统计") || s.contains("获取");
        if (s.startsWith("用户") || s.startsWith("我")) {
            if (!hasAction) {
                return false;
            }
            // 原四条件的③④：明确提到工具 + 过程元话语（正常回答如"我调用了X工具，因为…"不含元话语，放行）
            boolean hasTool = s.contains("工具");
            boolean hasMeta = containsAny(s, "本次查询", "直接调用", "无需参数", "无需额外",
                    "完成本次", "将调用", "先调用", "接下来", "我将", "以便后续");
            return hasTool && hasMeta;
        }
        if (s.startsWith("正在")) {
            if (!hasAction) {
                return false;
            }
            // 「正在为你/为您…」第一人称过程播报——强特征
            return s.contains("正在为你") || s.contains("正在为您");
        }
        return false;
    }

    /**
     * 整段文本的独白摘除（非流式，用于两处）：
     * ① fallback 把 reasoning 回退为正文前——reasoning 是多句独白连发，流式首句判定只删得了第一句；
     * ② 历史轮次的 assistant 正文注入扣子上下文前——避免上轮独白被模型模仿腔调。
     * <p>按句末标点分句，逐句判定命中即删；剩余内容拼接返回（全删光返回空串，
     * 调用方以空串决定不回退/不注入）。</p>
     */
    public static String stripProcessNarration(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean end = false;
            for (char e : SENTENCE_ENDS) {
                if (c == e) {
                    end = true;
                    break;
                }
            }
            if (!end) {
                continue;
            }
            String sentence = text.substring(start, i + 1);
            if (!isProcessPreamble(sentence)) {
                kept.append(sentence);
            }
            start = i + 1;
        }
        if (start < text.length()) {
            String tail = text.substring(start);
            if (!isProcessPreamble(tail)) {
                kept.append(tail);
            }
        }
        return kept.toString().trim();
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) {
            if (s.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** 返回第一个句末标点的下标；没有返回 -1 */
    private static int firstSentenceEnd(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            for (char e : SENTENCE_ENDS) {
                if (c == e) {
                    return i;
                }
            }
        }
        return -1;
    }
}
