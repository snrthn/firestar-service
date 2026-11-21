package com.firestar.ai.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolLoopPolicy 受控多步工具链策略单测：锁住步数预算 + 重复调用检测边界，
 * 防止哪天有人把续轮 tools 裸放开又没护栏，模型原地打转烧 token。
 */
class ToolLoopPolicyTest {

    @Test
    void shouldOfferTools_withinBudget_thenStop() {
        assertTrue(ToolLoopPolicy.shouldOfferTools(0, true), "首轮下发 tools");
        assertTrue(ToolLoopPolicy.shouldOfferTools(3, true), "第 4 轮（index 3）仍是工具轮");
        assertFalse(ToolLoopPolicy.shouldOfferTools(4, true), "第 5 轮强制总结，不再下发 tools");
        assertFalse(ToolLoopPolicy.shouldOfferTools(0, false), "无可用工具时不下发");
        assertEquals(4, ToolLoopPolicy.MAX_TOOL_ROUNDS);
    }

    @Test
    void signature_normalizesWhitespace_butKeepsToolAndArgs() {
        assertEquals("search_projects|{\"kw\":\"A\"}",
                ToolLoopPolicy.signature("  search_projects ", "{\"kw\": \"A\"}"),
                "参数空白归一、工具名 trim，判重不被格式差异绕过");
        assertFalse(ToolLoopPolicy.signature("search_projects", "{\"kw\":\"A\"}")
                .equals(ToolLoopPolicy.signature("query_shipment_detail", "{\"kw\":\"A\"}")),
                "不同工具不判重");
        assertFalse(ToolLoopPolicy.signature("search_projects", "{\"kw\":\"A\"}")
                .equals(ToolLoopPolicy.signature("search_projects", "{\"kw\":\"B\"}")),
                "同工具不同参数不判重（合法的多步查询）");
    }

    @Test
    void hasFreshCalls_detectsStall_afterMarkSeen() {
        Set<String> seen = ToolLoopPolicy.newSeen();
        String s1 = ToolLoopPolicy.signature("search_projects", "{\"kw\":\"A\"}");
        String s2 = ToolLoopPolicy.signature("query_shipment_detail", "{\"id\":9}");

        assertTrue(ToolLoopPolicy.hasFreshCalls(List.of(s1, s2), seen), "首轮都是新调用");
        ToolLoopPolicy.markSeen(List.of(s1, s2), seen);

        assertTrue(ToolLoopPolicy.hasFreshCalls(List.of(s1, "new|{}"), seen), "混入一个新调用就不算停滞");
        assertFalse(ToolLoopPolicy.hasFreshCalls(List.of(s1, s1), seen), "全是重复调用 → 停滞，下一轮停发 tools");
        assertFalse(ToolLoopPolicy.hasFreshCalls(List.of(), seen), "空调用也视为无新意");
    }
}
