package com.firestar.ai.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ToolCall;
import com.firestar.ai.spi.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolAuditFrame：直连工具审计帧载荷")
class ToolAuditFrameTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("1. 审计帧 type=tool_call，携带全部审计字段")
    void frameCarriesAllAuditFields() throws Exception {
        ToolCall call = new ToolCall("tc-1", "search_knowledge", "{\"query\":\"报销\"}");
        ToolResult result = new ToolResult("tc-1", "search_knowledge", "{\"hits\":3}", false);

        ChatChunk chunk = ToolAuditFrame.of(System.currentTimeMillis() - 12, call, result, mapper);

        assertNotNull(chunk);
        assertEquals("tool_call", chunk.type());
        JsonNode data = mapper.readTree(chunk.data());
        assertEquals("search_knowledge", data.get("toolName").asText());
        assertEquals("{\"query\":\"报销\"}", data.get("argsJson").asText());
        assertEquals("{\"hits\":3}", data.get("resultJson").asText());
        assertFalse(data.get("isError").asBoolean());
        assertTrue(data.get("latencyMs").asLong() >= 12);
    }

    @Test
    @DisplayName("2. 错误工具的结果也记 isError=true")
    void errorToolRecorded() throws Exception {
        ToolCall call = new ToolCall("tc-2", "search_projects", "{}");
        ToolResult result = new ToolResult("tc-2", "search_projects", "{\"error\":\"无权限\"}", true);

        ChatChunk chunk = ToolAuditFrame.of(System.currentTimeMillis(), call, result, mapper);

        JsonNode data = mapper.readTree(chunk.data());
        assertTrue(data.get("isError").asBoolean());
        assertEquals("{\"error\":\"无权限\"}", data.get("resultJson").asText());
    }

    @Test
    @DisplayName("3. 草稿工具同样发审计帧（draft 帧另发，审计不漏）")
    void draftToolAlsoAudited() throws Exception {
        ToolCall call = new ToolCall("tc-3", "draft_shipment_create", "{\"projectId\":1}");
        ToolResult result = new ToolResult("tc-3", "draft_shipment_create", "{\"preview\":{}}", false);

        ChatChunk chunk = ToolAuditFrame.of(System.currentTimeMillis(), call, result, mapper);

        assertNotNull(chunk);
        assertEquals("draft_shipment_create", mapper.readTree(chunk.data()).get("toolName").asText());
    }

    @Test
    @DisplayName("4. 往返：of 产帧 → parse 还原，字段无损（Controller 消费路径）")
    void roundTripOfParse() {
        ToolCall call = new ToolCall("tc-4", "search_knowledge", "{\"query\":\"飞星报销\"}");
        ToolResult result = new ToolResult("tc-4", "search_knowledge", "{\"hits\":3}", false);

        ChatChunk chunk = ToolAuditFrame.of(System.currentTimeMillis() - 7, call, result, mapper);
        ToolAuditFrame.Data data = ToolAuditFrame.parse(chunk, mapper);

        assertNotNull(data);
        assertEquals("search_knowledge", data.toolName());
        assertEquals("{\"query\":\"飞星报销\"}", data.argsJson());
        assertEquals("{\"hits\":3}", data.resultJson());
        assertFalse(data.isError());
        assertTrue(data.latencyMs() >= 7);
    }

    @Test
    @DisplayName("5. parse 对非 tool_call 帧 / 坏 JSON 返回 null，不抛异常")
    void parseIgnoresInvalidFrames() {
        assertNull(ToolAuditFrame.parse(ChatChunk.token("hi"), mapper));
        assertNull(ToolAuditFrame.parse(ChatChunk.toolCall("not-json"), mapper));
        assertNull(ToolAuditFrame.parse(null, mapper));
    }
}
