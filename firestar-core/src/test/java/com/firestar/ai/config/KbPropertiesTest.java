package com.firestar.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KbProperties：扩展点开关默认值 + fail-fast 校验")
class KbPropertiesTest {

    @Test
    @DisplayName("1. 默认值全部指向当前唯一实现，可直接启动")
    void defaultsAreValid() {
        KbProperties p = new KbProperties();
        assertDoesNotThrow(p::validate);
        assertEquals("keyword", p.getRetriever());
        assertEquals("rule", p.getRerank());
        assertEquals("allow-all", p.getEgressPolicy());
        assertEquals("noop", p.getPublishGate());
    }

    @Test
    @DisplayName("2. 配成未实现的检索源 → 启动失败且错误信息列出可选值")
    void unknownRetrieverFailsFast() {
        KbProperties p = new KbProperties();
        p.setRetriever("vector");
        IllegalStateException ex = assertThrows(IllegalStateException.class, p::validate);
        assertTrue(ex.getMessage().contains("kb.retriever"));
        assertTrue(ex.getMessage().contains("vector"));
        assertTrue(ex.getMessage().contains("keyword"), "应提示当前可选值 keyword");
    }

    @Test
    @DisplayName("3. 三个扩展点各自校验：rerank/egress/publish 非法值都 fail-fast")
    void otherSwitchesFailFast() {
        KbProperties a = new KbProperties();
        a.setRerank("llm");
        assertTrue(assertThrows(IllegalStateException.class, a::validate).getMessage().contains("rule"));

        KbProperties b = new KbProperties();
        b.setEgressPolicy("sensitive-aware");
        assertTrue(assertThrows(IllegalStateException.class, b::validate).getMessage().contains("allow-all"));

        KbProperties c = new KbProperties();
        c.setPublishGate("flowable");
        assertTrue(assertThrows(IllegalStateException.class, c::validate).getMessage().contains("noop"));
    }

    @Test
    @DisplayName("3b. model 重排是 V214 合法值（此前只有 rule）")
    void modelRerankIsValid() {
        KbProperties p = new KbProperties();
        p.setRerank("model");
        assertDoesNotThrow(p::validate);
    }

    @Test
    @DisplayName("4. null 值也 fail-fast（防止配置被显式置空后静默失效）")
    void nullValueFailsFast() {
        KbProperties p = new KbProperties();
        p.setRetriever(null);
        assertThrows(IllegalStateException.class, p::validate);
    }

    @Test
    @DisplayName("5. hybrid 是合法检索源（P1#5），单测无 EmbeddingProperties 时跳过联动校验")
    void hybridIsValid() {
        KbProperties p = new KbProperties();
        p.setRetriever("hybrid");
        assertDoesNotThrow(p::validate);
        assertEquals("hybrid", p.getRetriever());
    }
}
