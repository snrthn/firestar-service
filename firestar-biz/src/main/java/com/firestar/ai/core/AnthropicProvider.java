package com.firestar.ai.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ChatMessage;
import com.firestar.ai.spi.ChatProvider;
import com.firestar.ai.spi.ToolCall;
import com.firestar.ai.spi.ToolResult;
import com.firestar.ai.spi.ToolSpec;
import com.firestar.ai.spi.TurnRequest;
import com.firestar.ai.tool.ToolExecutor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Anthropic 原生供应商实现（Messages API，{@code POST /v1/messages}）。
 *
 * <p>与 {@link OpenAIProvider} 的 OpenAI 兼容协议不同，Anthropic Messages API 自成体系：
 * <ul>
 *   <li>鉴权用 {@code x-api-key} + {@code anthropic-version} 头，不是 Bearer；</li>
 *   <li>system 提示是<b>顶层 system 字段</b>（字符串），不放在 messages 数组里；</li>
 *   <li>工具声明是 {@code {name, description, input_schema}}，不是 OpenAI 的 function 包装；</li>
 *   <li>流式是 content block 事件（text_delta / input_json_delta），不是 choices[0].delta；</li>
 *   <li>工具结果以 {@code role:user} 的 {@code tool_result} content block 回传，不是 role:tool。</li>
 * </ul>
 * 故本类独立实现整条协议链路，仅复用 {@link ToolExecutor}（工具执行 / 权限 / 脱敏与供应商无关）。</p>
 *
 * <p><b>页面填法：</b>供应商选 Anthropic，Base URL 填 {@code https://api.anthropic.com}
 * （代码自动拼 /v1/messages），模型填 {@code claude-sonnet-4-6} 等，ApiKey 填 Console key。
 * 附加参数(JSON)可选：{@code {"maxTokens":8192,"anthropicVersion":"2023-06-01"}}，
 * 不填则用默认值 maxTokens=4096 / anthropicVersion=2023-06-01。</p>
 *
 * <p>逐帧下发文本增量（含 thinking 模型的 thinking_delta），tool_use 按 index 聚合后本地执行，
 * 结果以 tool_result 回传续轮，最多 5 轮防挂起；draft_ 前缀工具额外下发草稿帧。</p>
 */
public class AnthropicProvider implements ChatProvider {

    private static final String DEFAULT_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int MAX_ROUNDS = 5;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String version;
    private final int maxTokens;
    private final ObjectMapper objectMapper;
    private final ToolExecutor toolExecutor;
    private final HttpClient http;

    public AnthropicProvider(String baseUrl, String apiKey, String model, String extraJson,
                             ObjectMapper objectMapper, ToolExecutor toolExecutor,
                             HttpClientFactory httpClientFactory) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.toolExecutor = toolExecutor;
        this.http = httpClientFactory.newClient(Duration.ofSeconds(10));

        String v = DEFAULT_VERSION;
        int mt = DEFAULT_MAX_TOKENS;
        if (extraJson != null && !extraJson.isBlank()) {
            try {
                JsonNode j = objectMapper.readTree(extraJson);
                if (j.hasNonNull("anthropicVersion")) v = j.get("anthropicVersion").asText();
                else if (j.hasNonNull("anthropic_version")) v = j.get("anthropic_version").asText();
                if (j.hasNonNull("maxTokens")) mt = j.get("maxTokens").asInt();
                else if (j.hasNonNull("max_tokens")) mt = j.get("max_tokens").asInt();
            } catch (Exception ignored) {
                // 附加参数非本供应商格式 / 非法 JSON：忽略并用默认值，不让配置页的小笔误拖垮对话
            }
        }
        this.version = v;
        this.maxTokens = mt > 0 ? mt : DEFAULT_MAX_TOKENS;
    }

    @Override
    public String name() {
        return "anthropic";
    }

    /** Anthropic 无外部会话概念，返回本地稳定标识即可 */
    @Override
    public String createSession(String userRef) {
        return "anthropic-" + userRef;
    }

    @Override
    public void streamChat(TurnRequest req, Consumer<ChatChunk> onChunk) throws Exception {
        // 拆 system：Anthropic 要求 system 是顶层字段，messages 数组只放 user/assistant
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage m : req.messages()) {
            if ("system".equals(m.role())) {
                if (system.length() > 0) system.append("\n\n");
                system.append(m.content() == null ? "" : m.content());
            } else {
                messages.add(textMessage(m.role(), m.content()));
            }
        }
        List<ToolSpec> tools = req.tools() == null ? List.of() : req.tools();

        int rounds = 0;
        while (rounds < MAX_ROUNDS) {
            rounds++;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model == null || model.isBlank() ? "claude-sonnet-4-6" : model);
            payload.put("max_tokens", maxTokens);
            payload.put("stream", true);
            if (system.length() > 0) {
                payload.put("system", system.toString());
            }
            payload.put("messages", messages);
            // 首轮带 tools；续轮（已执行过工具）不带，让模型基于结果直接总结，避免再次触发
            if (rounds == 1 && !tools.isEmpty()) {
                payload.put("tools", toAnthropicTools(tools));
            }
            String body = objectMapper.writeValueAsString(payload);

            HttpRequest hr = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/$", "") + "/v1/messages"))
                    .header("x-api-key", apiKey == null ? "" : apiKey)
                    .header("anthropic-version", version)
                    .header("content-type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> resp = http.send(hr, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("Anthropic messages 失败 HTTP " + resp.statusCode() + ": " + err);
            }

            RoundResult r = parseSse(resp.body(), onChunk);
            if (r.toolUses.isEmpty()) {
                onChunk.accept(ChatChunk.done());
                return;
            }

            // 把本轮 assistant 的 content block（文本 + tool_use）原样回传，保持工具调用上下文
            List<Map<String, Object>> assistantContent = new ArrayList<>();
            if (!r.content.isEmpty()) {
                Map<String, Object> textBlock = new LinkedHashMap<>();
                textBlock.put("type", "text");
                textBlock.put("text", r.content);
                assistantContent.add(textBlock);
            }
            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (ToolUse tu : r.toolUses.values()) {
                Map<String, Object> useBlock = new LinkedHashMap<>();
                useBlock.put("type", "tool_use");
                useBlock.put("id", tu.id);
                useBlock.put("name", tu.name);
                useBlock.put("input", tu.inputNode(objectMapper));
                assistantContent.add(useBlock);

                ToolCall call = new ToolCall(tu.id, tu.name, tu.arguments(objectMapper));
                long toolStart = System.currentTimeMillis();
                ToolResult tr = toolExecutor.execute(call, req.username(), req.isAdmin());
                // N-13：所有工具统一发审计帧，由 AiChatController 用本地 convId 落库。
                ChatChunk auditFrame = ToolAuditFrame.of(toolStart, call, tr, objectMapper);
                if (auditFrame != null) {
                    onChunk.accept(auditFrame);
                }
                if (tu.name != null && tu.name.startsWith("draft_")) {
                    try {
                        String draftPayload = objectMapper.writeValueAsString(Map.of(
                                "toolName", tu.name,
                                "argsJson", tu.arguments(objectMapper),
                                "resultJson", tr.contentJson()));
                        onChunk.accept(ChatChunk.draft(draftPayload));
                    } catch (Exception ignored) {
                        // 草稿事件失败不影响主流程
                    }
                }
                Map<String, Object> resultBlock = new LinkedHashMap<>();
                resultBlock.put("type", "tool_result");
                resultBlock.put("tool_use_id", tu.id);
                resultBlock.put("content", tr.contentJson() == null ? "" : tr.contentJson());
                if (tr.isError()) {
                    resultBlock.put("is_error", true);
                }
                toolResults.add(resultBlock);
            }
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", assistantContent);
            messages.add(assistantMsg);

            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", toolResults);
            messages.add(userMsg);
        }
        onChunk.accept(ChatChunk.done());
    }

    /** 普通文本消息（content 为字符串，Anthropic 合法写法） */
    private static Map<String, Object> textMessage(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        return m;
    }

    /** ToolRegistry 的 ToolSpec → Anthropic tools 数组：input_schema 直接是 JSON Schema 对象 */
    static List<Map<String, Object>> toAnthropicTools(List<ToolSpec> specs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec s : specs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("description", s.description());
            try {
                m.put("input_schema", objectMapperStatic.readTree(s.parametersJson()));
            } catch (Exception e) {
                m.put("input_schema", objectMapperStatic.createObjectNode());
            }
            out.add(m);
        }
        return out;
    }

    // 仅供静态工具方法解析 schema 用；实例解析统一走 this.objectMapper
    private static final ObjectMapper objectMapperStatic = new ObjectMapper();

    /**
     * 解析一轮 SSE：逐帧下发文本增量，聚合 tool_use 的 input_json_delta 分片。
     * 包可见便于单测注入 mock 流。
     */
    static RoundResult parseSse(InputStream in, Consumer<ChatChunk> onChunk) throws Exception {
        RoundResult r = new RoundResult();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue; // event: / 空行等忽略
            }
            String data = line.substring(5).strip();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                continue;
            }
            JsonNode node;
            try {
                node = objectMapperStatic.readTree(data);
            } catch (Exception ignore) {
                continue; // 单个畸形帧不影响整条流
            }
            JsonNode typeNode = node.get("type");
            if (typeNode == null) {
                continue;
            }
            String type = typeNode.asText();
            switch (type) {
                case "content_block_start" -> {
                    JsonNode block = node.get("content_block");
                    if (block != null && "tool_use".equals(block.path("type").asText())) {
                        int idx = node.path("index").asInt(r.toolUses.size());
                        ToolUse tu = new ToolUse();
                        tu.id = block.path("id").asText("toolu_" + System.nanoTime());
                        tu.name = block.path("name").asText("");
                        JsonNode input = block.get("input");
                        if (input != null && !input.isNull()) {
                            tu.startInput = input.toString();
                        }
                        r.toolUses.put(idx, tu);
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = node.get("delta");
                    if (delta == null) {
                        break;
                    }
                    String dt = delta.path("type").asText();
                    if ("text_delta".equals(dt) || "thinking_delta".equals(dt)) {
                        String t = delta.path("text").asText("");
                        if (!t.isEmpty()) {
                            r.content += t;
                            onChunk.accept(ChatChunk.token(t)); // 逐帧下发，保证流式
                        }
                    } else if ("input_json_delta".equals(dt)) {
                        int idx = node.path("index").asInt(0);
                        ToolUse tu = r.toolUses.computeIfAbsent(idx, k -> new ToolUse());
                        tu.jsonBuf.append(delta.path("partial_json").asText(""));
                    }
                }
                case "error" -> {
                    JsonNode err = node.get("error");
                    String msg = err == null ? "Anthropic stream error" : err.path("message").asText("Anthropic stream error");
                    throw new IllegalStateException(msg);
                }
                default -> {
                    // message_start / content_block_stop / message_delta / message_stop / ping：无需处理
                }
            }
        }
        return r;
    }

    /** 一轮聚合结果（包可见便于单测断言） */
    static class RoundResult {
        String content = "";
        final Map<Integer, ToolUse> toolUses = new LinkedHashMap<>();
    }

    /** 单个 tool_use 的聚合：start 时的 id/name + 分片拼接的 input JSON */
    static class ToolUse {
        String id;
        String name;
        String startInput;
        final StringBuilder jsonBuf = new StringBuilder();

        String arguments(ObjectMapper om) {
            String s = jsonBuf.toString();
            if (!s.isBlank()) {
                return s;
            }
            return startInput == null ? "{}" : startInput;
        }

        JsonNode inputNode(ObjectMapper om) {
            try {
                JsonNode n = om.readTree(arguments(om));
                return n.isObject() ? n : om.createObjectNode();
            } catch (Exception e) {
                return om.createObjectNode();
            }
        }
    }
}
