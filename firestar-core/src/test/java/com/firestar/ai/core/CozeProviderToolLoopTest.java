package com.firestar.ai.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ChatMessage;
import com.firestar.ai.spi.ToolCall;
import com.firestar.ai.spi.ToolExecutionPort;
import com.firestar.ai.spi.ToolResult;
import com.firestar.ai.spi.ToolSpec;
import com.firestar.ai.spi.TurnRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端插件链路端到端取证：假扣子服务先推 requires_action、收到 submit 后再吐正文。
 * 守护三个看不见的机制：①工具真的本地执行；②submit 请求带对的 tool_call_id/output；
 * ③请求流的 done 被吞掉，只有最终流才发一次 done（前端才不会提前停接收）。
 */
class CozeProviderToolLoopTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;

    private final List<ToolCall> executed = new ArrayList<>();
    private final List<JsonNode> submits = new ArrayList<>();
    private final AtomicInteger chatCalls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/chat", this::handleChat);
        server.createContext("/v3/chat/submit_tool_outputs", this::handleSubmit);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handleChat(HttpExchange ex) throws IOException {
        chatCalls.incrementAndGet();
        String sse = "event:conversation.chat.requires_action\n"
                + "data:{\"id\":\"chat-1\",\"conversation_id\":\"conv-1\",\"status\":\"requires_action\","
                + "\"required_action\":{\"type\":\"submit_tool_outputs\",\"submit_tool_outputs\":{\"tool_calls\":["
                + "{\"id\":\"tc-1\",\"type\":\"function\",\"function\":{\"name\":\"search_org\",\"arguments\":\"{\\\"q\\\":\\\"研发\\\"}\"}}"
                + "]}}}\n\n"
                + "event:done\ndata:{\"code\":0}\n\n";
        sendSse(ex, sse);
    }

    private void handleSubmit(HttpExchange ex) throws IOException {
        submits.add(mapper.readTree(ex.getRequestBody().readAllBytes()));
        String sse = "event:conversation.message.delta\n"
                + "data:{\"type\":\"answer\",\"content\":\"负责人是杨虎宁\"}\n\n"
                + "event:done\ndata:{\"code\":0}\n\n";
        sendSse(ex, sse);
    }

    private static void sendSse(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private TurnRequest req() {
        return new TurnRequest("conv-1",
                List.of(ChatMessage.of("user", "查一下研发中心负责人")),
                List.of(new ToolSpec("search_org", "查询组织结构", "{}")),
                null, "tester", false);
    }

    @Test
    @DisplayName("端插件：requires_action → 本地执行工具 → submit 结果 → 后续流正常出正文，只 done 一次")
    void endToEndToolLoop() throws Exception {
        ToolExecutionPort fake = (call, username, isAdmin) -> {
            executed.add(call);
            assertEquals("tester", username);
            return new ToolResult(call.id(), call.name(), "{\"负责人\":\"杨虎宁\"}", false);
        };
        CozeProvider provider = new CozeProvider(baseUrl, "bot-1", "pat", mapper, fake, new HttpClientFactory());
        List<ChatChunk> chunks = new ArrayList<>();
        provider.streamChat(req(), chunks::add);

        assertEquals(1, executed.size());
        assertEquals("search_org", executed.get(0).name());
        assertEquals("{\"q\":\"研发\"}", executed.get(0).argumentsJson());
        assertEquals(1, chatCalls.get());
        assertEquals(1, submits.size());
        JsonNode submit = submits.get(0);
        assertTrue(submit.path("stream").asBoolean(false));
        JsonNode out = submit.path("tool_outputs").path(0);
        assertEquals("tc-1", out.path("tool_call_id").asText());
        // 统一 text 契约：原始结果被包成 {"text": ...}，扣子侧只需配一个 text 字段
        JsonNode outputNode = mapper.readTree(out.path("output").asText());
        assertEquals("{\"负责人\":\"杨虎宁\"}", outputNode.path("text").asText());

        List<ChatChunk> tokens = chunks.stream().filter(c -> "token".equals(c.type())).toList();
        assertEquals(1, tokens.size());
        assertEquals("负责人是杨虎宁", tokens.get(0).text());
        assertEquals(1, chunks.stream().filter(c -> "done".equals(c.type())).count());
        assertTrue(chunks.stream().anyMatch(c -> "tool_call".equals(c.type())), "工具审计帧必须下发");
    }

    @Test
    @DisplayName("工具不在用户可见清单时拒绝执行，但仍提交结果避免扣子挂起")
    void nonWhitelistedToolRejected() throws Exception {
        ToolExecutionPort fake = (call, username, isAdmin) -> {
            executed.add(call);
            return new ToolResult(call.id(), call.name(), "{}", false);
        };
        CozeProvider provider = new CozeProvider(baseUrl, "bot-1", "pat", mapper, fake, new HttpClientFactory());
        List<ChatChunk> chunks = new ArrayList<>();
        // 可见清单里只有另一个工具：假扣子仍吐 search_org → 必须被拒
        TurnRequest narrowReq = new TurnRequest("conv-1",
                List.of(ChatMessage.of("user", "查一下研发中心负责人")),
                List.of(new ToolSpec("other_tool", "无关工具", "{}")),
                null, "tester", false);
        provider.streamChat(narrowReq, chunks::add);

        assertTrue(executed.isEmpty(), "白名单外工具不得执行");
        assertEquals(1, submits.size());
        assertTrue(submits.get(0).path("tool_outputs").path(0).path("output").asText().contains("拒绝执行"));
        assertEquals("text", mapper.readTree(submits.get(0).path("tool_outputs").path(0).path("output").asText()).fieldNames().next());
        assertEquals(1, chunks.stream().filter(c -> "done".equals(c.type())).count());
    }

    @Test
    @DisplayName("submit 输出统一 text 契约：对象/数组/纯文本都包成单字段 text")
    void wrapOutputIntoSingleTextField() throws Exception {
        // 普通对象 → 包成 {"text": <原对象字符串>}
        JsonNode obj = mapper.readTree(CozeProvider.wrapToolOutputForCoze("{\"id\":1,\"name\":\"研发部\"}", mapper));
        assertEquals(1, obj.size());
        assertEquals("{\"id\":1,\"name\":\"研发部\"}", obj.path("text").asText());

        // 数组结果 → 包成 text（扣子 schema 仍是单字段，不因根类型变化而瞎）
        JsonNode arr = mapper.readTree(CozeProvider.wrapToolOutputForCoze("[{\"id\":1},{\"id\":2}]", mapper));
        assertEquals(1, arr.size());
        assertEquals("[{\"id\":1},{\"id\":2}]", arr.path("text").asText());

        // 非 JSON 纯文本 → 包成 text
        JsonNode txt = mapper.readTree(CozeProvider.wrapToolOutputForCoze("负责人是杨虎宁", mapper));
        assertEquals("负责人是杨虎宁", txt.path("text").asText());

        // null/空 → 安全兜底
        JsonNode nil = mapper.readTree(CozeProvider.wrapToolOutputForCoze(null, mapper));
        assertEquals("", nil.path("text").asText());
    }

    @Test
    @DisplayName("已是 text 契约的结果原样透传，不双重包装（query_orgs 场景）")
    void wrapOutputNoDoubleWrap() throws Exception {
        String alreadyText = "{\"text\":\"1. 火星总部\\n  1.1 研发中心\"}";
        assertEquals(alreadyText, CozeProvider.wrapToolOutputForCoze(alreadyText, mapper));
    }

    @Test
    @DisplayName("官方 requires_action 样例解析：tool_call_id / name / arguments 三件齐全")
    void parseOfficialRequiresActionSample() throws Exception {
        JsonNode data = mapper.readTree("{\"id\":\"743436994609809\",\"conversation_id\":\"743436839342099\","
                + "\"required_action\":{\"type\":\"submit_tool_outputs\",\"submit_tool_outputs\":{\"tool_calls\":["
                + "{\"id\":\"BUJJS0JKQ0dHR0VeFkNCQF5HEEZBXhJFEUJeEhFCRkESSktDREISSUI=\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_rain_probability\",\"arguments\":\"{\\\"location\\\":\\\"河南\\\"}\"}}]}}}");
        List<CozeProvider.ToolInvocation> calls = CozeProvider.parseToolCalls(data);
        assertEquals(1, calls.size());
        assertEquals("BUJJS0JKQ0dHR0VeFkNCQF5HEEZBXhJFEUJeEhFCRkESSktDREISSUI=", calls.get(0).id());
        assertEquals("get_rain_probability", calls.get(0).name());
        assertEquals("{\"location\":\"河南\"}", calls.get(0).arguments());
    }
}
