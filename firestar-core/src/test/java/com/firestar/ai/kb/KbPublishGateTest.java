package com.firestar.ai.kb;

import com.firestar.ai.entity.KbDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KbPublishGate：默认发布门 + 决策结果")
class KbPublishGateTest {

    @Test
    @DisplayName("1. Noop 发布门对任意文档放行")
    void noopAllows() {
        KbPublishGate gate = new NoopKbPublishGate();
        PublishDecision d = gate.beforePublish(new KbDoc());
        assertNotNull(d);
        assertTrue(d.allowed());
        assertNull(d.reason());
    }

    @Test
    @DisplayName("2. PublishDecision 工厂：allow/deny 语义正确")
    void decisionFactories() {
        assertTrue(PublishDecision.allow().allowed());
        assertNull(PublishDecision.allow().reason());
        PublishDecision denied = PublishDecision.deny("机密文档禁止发布");
        assertFalse(denied.allowed());
        assertEquals("机密文档禁止发布", denied.reason());
    }
}
