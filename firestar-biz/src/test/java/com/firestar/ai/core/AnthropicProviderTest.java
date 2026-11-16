package com.firestar.ai.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ChatMessage;
import com.firestar.ai.spi.ToolSpec;
import com.firestar.ai.spi.TurnRequest;
import com.firestar.ai.tool.ToolExecutor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AnthropicProvider 协议链路单测（不连网、不起服务）。
 *
 * <p>直接喂 mock SSE 流，验证 Anthropic 协议里最容易写错的三处：
 * <ol>
 *   <li>text_delta / thinking_delta 逐帧下发 token（流式，不缓冲）；</li>
 *   <li>tool_use 的 id/name 来自 content_block_start，input 由 input_json_delta 分片拼接；</li>
 *   <li>toAnthropicTools 产出 Anthropic 专属的 input_schema（非 OpenAI function 包装）。</li>
 * </ol>
 */
class AnthropicProviderTest {

    private final ObjectMapper om = new ObjectMapper();

    private static ByteArrayInputStream sse(String raw) {
        return new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void textDeltaEmittedPerFrame() throws Exception {
        String stream = String.join("\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[]}}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"text\":\"思考中\"}}",
                "",
                "event: content_block_stop",
                "data: {\"type\":\"content_block_stop\",\"index\":0}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "");

        List<ChatChunk> chunks = new ArrayList<>();
        AnthropicProvider.RoundResult r = AnthropicProvider.parseSse(sse(stream), chunks::add);

        assertEquals(2, chunks.size(), "两个增量帧应逐帧下发，不合并");
        assertEquals("token", chunks.get(0).type());
        assertEquals("你好", chunks.get(0).text());
        assertEquals("思考中", chunks.get(1).text());
        assertEquals("你好思考中", r.content);
        assertTrue(r.toolUses.isEmpty());
    }

    @Test
    void toolUseAggregatesIdNameAndPartialJson() throws Exception {
        String stream = String.join("\n",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"稍等\"}}",
                "data: {\"type\":\"content_block_stop\",\"index\":0}",
                "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"search_projects\",\"input\":{}}}",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"keyword\\\":\"}}",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"火星\\\"}\"}}",
                "data: {\"type\":\"content_block_stop\",\"index\":1}",
                "data: {\"type\":\"message_stop\"}",
                "");

        List<ChatChunk> chunks = new ArrayList<>();
        AnthropicProvider.RoundResult r = AnthropicProvider.parseSse(sse(stream), chunks::add);

        assertEquals(1, chunks.size());
        assertEquals("稍等", chunks.get(0).text());
        assertEquals(1, r.toolUses.size());
        AnthropicProvider.ToolUse tu = r.toolUses.values().iterator().next();
        assertEquals("toolu_1", tu.id);
        assertEquals("search_projects", tu.name);
        // 分片必须拼成完整合法 JSON，供 ToolExecutor 消费
        assertEquals("火星", om.readTree(tu.arguments(om)).get("keyword").asText());
        assertEquals("火星", tu.inputNode(om).get("keyword").asText());
    }

    @Test
    void toolUseFallsBackToStartInputWhenNoDelta() throws Exception {
        String stream = String.join("\n",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"stats_overview\",\"input\":{\"range\":\"month\"}}}",
                "data: {\"type\":\"content_block_stop\",\"index\":0}",
                "data: {\"type\":\"message_stop\"}",
                "");

        AnthropicProvider.RoundResult r = AnthropicProvider.parseSse(sse(stream), c -> {});
        AnthropicProvider.ToolUse tu = r.toolUses.values().iterator().next();
        assertEquals("stats_overview", tu.name);
        assertEquals("month", om.readTree(tu.arguments(om)).get("range").asText(),
                "无 input_json_delta 时应回退用 content_block_start 的 input");
    }

    @Test
    void toolsUseAnthropicInputSchemaShape() {
        List<ToolSpec> specs = List.of(new ToolSpec("search_projects", "查项目", "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\"}}}"));
        List<Map<String, Object>> out = AnthropicProvider.toAnthropicTools(specs);

        assertEquals(1, out.size());
        Map<String, Object> tool = out.get(0);
        assertEquals("search_projects", tool.get("name"));
        assertEquals("查项目", tool.get("description"));
        assertTrue(tool.containsKey("input_schema"), "必须是 Anthropic 的 input_schema");
        assertTrue(!tool.containsKey("function"), "不能是 OpenAI 的 function 包装");
        com.fasterxml.jackson.databind.JsonNode schema =
                (com.fasterxml.jackson.databind.JsonNode) tool.get("input_schema");
        assertEquals("object", schema.get("type").asText());
        assertEquals("string", schema.get("properties").get("keyword").get("type").asText());
    }

    @Test
    void errorEventThrows() {
        String stream = "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"服务过载\"}}\n";
        try {
            AnthropicProvider.parseSse(sse(stream), c -> {});
            org.junit.jupiter.api.Assertions.fail("error 事件必须抛异常以触发熔断降级");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("服务过载"));
        } catch (Exception e) {
            org.junit.jupiter.api.Assertions.fail("应抛 IllegalStateException，实际: " + e.getClass());
        }
    }

    /**
     * 端到端验证真实发出的 HTTP 请求：用 JDK 内置 HttpServer 起随机端口 mock，
     * 抓取 path / x-api-key / anthropic-version / body，断言 system 抽到顶层、tools 用 input_schema。
     * ToolExecutor 打桩避免触碰业务 Service；跑完立即 stop，不占固定端口。
     */
    @Test
    void endToEndHttpRequestAssembly() throws Exception {
        AtomicReference<String> gotPath = new AtomicReference<>();
        AtomicReference<String> gotKey = new AtomicReference<>();
        AtomicReference<String> gotVersion = new AtomicReference<>();
        AtomicReference<String> gotBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange ex) -> {
            gotPath.set(ex.getRequestURI().getPath());
            gotKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            gotVersion.set(ex.getRequestHeaders().getFirst("anthropic-version"));
            gotBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] sse = ("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n"
                    + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"pong\"}}\n"
                    + "data: {\"type\":\"message_stop\"}\n").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, sse.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(sse);
            }
        });
        server.start();
        try {
            ToolExecutor executor = org.mockito.Mockito.mock(ToolExecutor.class);
            AnthropicProvider provider = new AnthropicProvider(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "sk-test", "claude-sonnet-4-6",
                    "{\"maxTokens\":8192}", om, executor, new HttpClientFactory());
            List<ChatChunk> chunks = new ArrayList<>();
            TurnRequest req = new TurnRequest("conv1",
                    List.of(ChatMessage.of("system", "遵守工具纪律"), ChatMessage.of("user", "ping")),
                    List.of(new ToolSpec("search_projects", "查项目", "{\"type\":\"object\",\"properties\":{}}")),
                    null, "tester", false);
            provider.streamChat(req, chunks::add);

            assertEquals("/v1/messages", gotPath.get(), "Base URL 应自动拼 /v1/messages");
            assertEquals("sk-test", gotKey.get(), "必须用 x-api-key 头，非 Bearer");
            assertEquals("2023-06-01", gotVersion.get(), "必须带 anthropic-version 头");
            assertEquals("token", chunks.get(0).type());
            assertEquals("pong", chunks.get(0).text());
            assertEquals("done", chunks.get(chunks.size() - 1).type());

            JsonNode body = om.readTree(gotBody.get());
            assertEquals("claude-sonnet-4-6", body.get("model").asText());
            assertEquals(8192, body.get("max_tokens").asInt(), "extraJson 的 maxTokens 应生效");
            assertEquals("遵守工具纪律", body.get("system").asText(), "system 必须在顶层，不进 messages");
            assertEquals(1, body.get("messages").size(), "messages 不应含 system 消息");
            assertEquals("ping", body.get("messages").get(0).get("content").asText());
            assertTrue(body.get("tools").get(0).has("input_schema"), "tools 应为 Anthropic input_schema 结构");
            assertTrue(body.get("stream").asBoolean());
        } finally {
            server.stop(0);
        }
    }
}
