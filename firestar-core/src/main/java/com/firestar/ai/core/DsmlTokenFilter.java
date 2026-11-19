package com.firestar.ai.core;

import java.util.function.Consumer;

/**
 * 流式正文中的 DSML 伪工具调用过滤器（跨帧安全）。
 *
 * <p>背景：部分推理模型（实测 DeepSeek）不输出标准 {@code tool_calls} 数组，而是把工具调用
 * 以 {@code <|DSML|> ... </|DSML|>} 文本写进 content。这些协议标记此前被当作普通 token
 * 下发并原样落库，用户在气泡里看到「&lt;|DSML|&gt; invoke name="search_knowledge"」等乱文。</p>
 *
 * <p>本过滤器位于 SSE token 出口：缓冲正文字串，识别完整的 DSML 块并整块丢弃，其余文本
 * 原样（含前导空格、换行）向下游透传。标记可能被模型拆到多个帧（{@code <|DS} + {@code ML|>}），
 * 故每次只发射「确定不属于标记前缀」的部分；流结束时 flush 剩余文本。</p>
 *
 * <p>不是正则逐帧替换——逐帧会漏掉跨帧标记；也不是模型识别工具调用的替代方案——
 * 标准 tool_calls 仍由 Provider 处理，这里只做「不要让用户看到协议噪声」的兜底。</p>
 */
public final class DsmlTokenFilter {

    private static final String OPEN = "<|DSML|>";
    private static final String CLOSE = "</|DSML|>";
    /** DSML 的嵌套闭合标签：parameter/invoke 是块内部结束，calls 才是整个伪调用块结束 */
    private static final String[] NESTED_TAGS = {"calls>", "invoke>", "parameter>"};
    private static final String PREFIX = "\u0000PREFIX";

    private final Consumer<String> onText;
    private StringBuilder pending = new StringBuilder();
    private boolean inDsml;
    /** 上一个已发射正文的末字符：DSML 块前后若各带一个换行，合并成一个空行 */
    private Character lastEmitted;

    public DsmlTokenFilter(Consumer<String> onText) {
        this.onText = onText;
    }

    private void emit(String text) {
        if (text.isEmpty()) return;
        onText.accept(text);
        lastEmitted = text.charAt(text.length() - 1);
    }

    /** 喂入一个 token 增量；只发射已确定安全的部分 */
    public void accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        pending.append(chunk);
        drain(false);
    }

    /** 流结束：发射最后残留的非 DSML 文本（若仍停在 DSML 块内则整块丢弃） */
    public void finish() {
        drain(true);
    }

    private void drain(boolean flush) {
        while (pending.length() > 0) {
            if (inDsml) {
                int close = indexOfIgnoreCase(pending, CLOSE);
                if (close < 0) {
                    if (flush) {
                        // 未闭合的 DSML 块：整段都是噪声，丢弃
                        pending.setLength(0);
                        inDsml = false;
                    } else {
                        // 只留可能是 CLOSE 前缀的尾巴（应对跨帧拆开），其余立即丢弃
                        int hold = holdback(pending, CLOSE);
                        String tail = pending.substring(pending.length() - hold);
                        pending = new StringBuilder(tail);
                    }
                    return;
                }
                // CLOSE 后面可能跟 parameter>/invoke>（嵌套结束）或 calls>（整个块结束）。
                // 只认 calls> 为块结束，否则会提前退出，把剩余协议尾巴当用户正文显示。
                String after = pending.substring(close + CLOSE.length());
                String trimmed = after.stripLeading();
                String tag = nestedTagOf(trimmed);
                if (PREFIX.equals(tag) && !flush) {
                    // 标签名可能被拆帧（" cal" + "ls>"），留着下一帧再判断
                    pending = new StringBuilder(pending.substring(close));
                    return;
                }
                int consumed = close + CLOSE.length()
                        + (tag == null || PREFIX.equals(tag) ? 0 : (after.length() - trimmed.length()) + tag.length());
                pending.delete(0, consumed);
                if (tag == null || PREFIX.equals(tag) || "calls>".equalsIgnoreCase(tag)) {
                    inDsml = false;
                    // 块前正文以换行结尾、块后又紧跟换行 → 去掉一个，避免多余空行
                    if (lastEmitted != null && lastEmitted == '\n' && pending.length() > 0
                            && pending.charAt(0) == '\n') {
                        pending.deleteCharAt(0);
                    }
                }
                continue;
            }
            int open = indexOfIgnoreCase(pending, OPEN);
            if (open < 0) {
                int emitLen = pending.length();
                if (!flush) emitLen -= holdback(pending, OPEN);
                if (emitLen > 0) {
                    emit(pending.substring(0, emitLen));
                    pending.delete(0, emitLen);
                }
                return;
            }
            if (open > 0) emit(pending.substring(0, open));
            pending.delete(0, open + OPEN.length());
            inDsml = true;
        }
    }

    /** pending 结尾有多少个字符恰好是 marker 的前缀（可能是跨帧拆开的半个标记） */
    private static int holdback(StringBuilder pending, String marker) {
        int max = Math.min(pending.length(), marker.length() - 1);
        for (int n = max; n > 0; n--) {
            if (pending.substring(pending.length() - n).equalsIgnoreCase(marker.substring(0, n))) {
                return n;
            }
        }
        return 0;
    }

    /** trimmed 是否是嵌套标签的开头（完整返回标签名，部分前缀返回 PREFIX，否则 null） */
    private static String nestedTagOf(String trimmed) {
        for (String tag : NESTED_TAGS) {
            if (trimmed.length() >= tag.length()) {
                if (tag.regionMatches(true, 0, trimmed, 0, tag.length())) return tag;
            } else if (tag.regionMatches(true, 0, trimmed, 0, trimmed.length())) {
                return PREFIX;
            }
        }
        return null;
    }

    private static int indexOfIgnoreCase(StringBuilder haystack, String needle) {
        outer:
        for (int i = 0; i <= haystack.length() - needle.length(); i++) {
            for (int j = 0; j < needle.length(); j++) {
                if (Character.toLowerCase(haystack.charAt(i + j)) != Character.toLowerCase(needle.charAt(j))) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
