package com.firestar.ai.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AllowAllKbEgressPolicy：P0 全部放行")
class AllowAllKbEgressPolicyTest {

    @Test
    void allowAlwaysTrue() {
        AllowAllKbEgressPolicy policy = new AllowAllKbEgressPolicy();
        KbChunkHit hit = new KbChunkHit(1L, 1, "MD", "标题", "根", "sec", "内容", "kb:doc/1/v1/sec", 1.0);
        assertTrue(policy.allow(hit, new KbSearchContext(false, java.util.Set.of(), 0L)));
    }
}
