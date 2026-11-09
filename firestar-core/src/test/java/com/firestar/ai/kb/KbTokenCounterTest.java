package com.firestar.ai.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** KbTokenCounter 纯函数单测（不连库、不启 Spring）。 */
class KbTokenCounterTest {

    @Test
    @DisplayName("空 / null 返回 0")
    void blank() {
        assertEquals(0, KbTokenCounter.estimate(null));
        assertEquals(0, KbTokenCounter.estimate(""));
        assertEquals(0, KbTokenCounter.estimate("   \n\t "));
    }

    @Test
    @DisplayName("纯中文：每字 1 token")
    void cjk() {
        assertEquals(4, KbTokenCounter.estimate("你好世界"));
        assertEquals(1, KbTokenCounter.estimate("税"));
    }

    @Test
    @DisplayName("纯拉丁：每 4 非空白字符约 1 token，空白不计")
    void latin() {
        assertEquals(1, KbTokenCounter.estimate("abc"));          // 3 非空白 -> ceil(3/4)=1
        assertEquals(2, KbTokenCounter.estimate("hello"));        // 5 -> ceil(5/4)=2
        assertEquals(1, KbTokenCounter.estimate("a b c"));        // 空格不计，3 非空白
    }

    @Test
    @DisplayName("中英混排：CJK 按字 + 拉丁按 4 取上整")
    void mixed() {
        // 报销(2 CJK) + rule(4 拉丁 -> 1) = 3
        assertEquals(3, KbTokenCounter.estimate("报销rule"));
    }
}
