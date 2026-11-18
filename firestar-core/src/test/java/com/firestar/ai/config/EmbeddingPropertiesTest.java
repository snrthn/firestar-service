package com.firestar.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingPropertiesTest {

    @Test
    @DisplayName("1. 默认值：provider=none、dims=1024，校验通过")
    void defaultsValid() {
        EmbeddingProperties p = new EmbeddingProperties();
        assertDoesNotThrow(p::validate);
        assertEquals("none", p.getProvider());
        assertEquals(1024, p.getDims());
        assertFalse(p.isEnabled());
    }

    @Test
    @DisplayName("2. local / cloud 合法且 isEnabled=true")
    void localAndCloudValid() {
        EmbeddingProperties p = new EmbeddingProperties();
        p.setProvider("local");
        assertDoesNotThrow(p::validate);
        assertTrue(p.isEnabled());
        p.setProvider("cloud");
        assertDoesNotThrow(p::validate);
        assertTrue(p.isEnabled());
    }

    @Test
    @DisplayName("3. 非法 provider fail-fast，错误信息含可选值")
    void invalidProviderFailsFast() {
        EmbeddingProperties p = new EmbeddingProperties();
        p.setProvider("vector");
        IllegalStateException ex = assertThrows(IllegalStateException.class, p::validate);
        assertTrue(ex.getMessage().contains("none"), "错误应列出可选值");
        assertTrue(ex.getMessage().contains("local"));
        assertTrue(ex.getMessage().contains("cloud"));
    }

    @Test
    @DisplayName("4. dims<=0 fail-fast（改维需重建索引，必须先拦住）")
    void invalidDimsFailsFast() {
        EmbeddingProperties p = new EmbeddingProperties();
        p.setDims(0);
        assertThrows(IllegalStateException.class, p::validate);
        p.setDims(-3);
        IllegalStateException ex = assertThrows(IllegalStateException.class, p::validate);
        assertTrue(ex.getMessage().contains("重建"));
    }
}
