package com.firestar.ai.kb;

/**
 * 知识库 token 估算（纯函数，无外部依赖，可直接单测）。
 *
 * <p>设计 §3.4 红线：chunk 上限约 800 token。本估算为软上限用，不依赖任何 tokenizer 模型：
 * <ul>
 *   <li>CJK 字符（含 CJK 标点 / 全角）≈ 1 token；</li>
 *   <li>其余非空白字符（拉丁字母 / 数字 / 半角标点）每 4 个 ≈ 1 token；</li>
 *   <li>空白不计入。</li>
 * </ul>
 * 与项目既有的「中文为主、夹杂英文术语」财务文档分布吻合；切分只需近似，不必精确。</p>
 */
public final class KbTokenCounter {

    private KbTokenCounter() {
    }

    /** 估算一段文本的 token 数（空 / null 返回 0）。 */
    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int rest = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            if (isCjk(cp)) {
                cjk++;
            } else {
                rest++;
            }
            i += Character.charCount(cp);
        }
        return cjk + (int) Math.ceil(rest / 4.0);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3000 && cp <= 0x303F)      // CJK 符号和标点
                || (cp >= 0x3400 && cp <= 0x4DBF)   // 扩展 A
                || (cp >= 0x4E00 && cp <= 0x9FFF)   // 基本汉字
                || (cp >= 0xF900 && cp <= 0xFAFF)   // 兼容汉字
                || (cp >= 0xFF00 && cp <= 0xFFEF);  // 全角字符
    }
}
