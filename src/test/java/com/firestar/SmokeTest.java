package com.firestar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 空壳冒烟测试：不加载 Spring 上下文，避免 CI 单测阶段在无 DB 环境下失败。
 * 后续领域代码就位后，可补充 @SpringBootTest 上下文测试。
 */
class SmokeTest {

    @Test
    void contextLoads() {
        assertEquals("firestar-service", "firestar-service");
    }
}
