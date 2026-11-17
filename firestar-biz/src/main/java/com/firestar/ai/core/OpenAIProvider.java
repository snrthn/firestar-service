package com.firestar.ai.core;

import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ChatMessage;
import com.firestar.ai.spi.ChatProvider;
import com.firestar.ai.spi.ToolCall;
import com.firestar.ai.spi.TurnRequest;
import com.firestar.ai.spi.ToolResult;
import com.firestar.ai.spi.ToolSpec;
import com.firestar.ai.tool.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * OpenAI 兼容供应商实现（百炼 / DeepSeek 等，设计 §4.2）。
 *
 * <p>与 Coze 不同，OpenAI 系在请求里直接带 {@code tools} 字段、模型回 {@code tool_calls}，
 * 本类在阶段二补齐<b>工具调用本地聚合循环</b>：流式聚合 tool_calls（按 index 拼接 name+arguments）
 * → 本地经 {@link ToolExecutor} 执行 → 结果以 tool role 消息回传 → 续生成（最多 5 轮防挂起）。
 * 工具声明由 ToolRegistry 提供（与扣子侧同一份 schema），无需在供应商控制台手动注册。</p>
 */
public class OpenAIProvider implements ChatProvider {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final ToolExecutor toolExecutor;
    private final HttpClient http;

    public OpenAIProvider(String baseUrl, String apiKey, String model, ObjectMapper objectMapper,
                          ToolExecutor toolExecutor, HttpClientFactory httpClientFactory) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.toolExecutor = toolExecutor;
        this.http = httpClientFactory.newClient(Duration.ofSeconds(10));
    }

    @Override
    public String name() {
        return "openai";
    }

    /** OpenAI 系无外部会话概念，返回本地稳定标识即可 */
    @Override
    public String createSession(String userRef) {
        return "openai-" + userRef;
    }

    @Override
    public void streamChat(TurnRequest req, Consumer<ChatChunk> onChunk) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage m : req.messages()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("role", m.role());
            // content 可能为 null（assistant 带 tool_calls 的消息），OpenAI 允许
            mm.put("content", m.content());
            messages.add(mm);
        }
        List<ToolSpec> tools = req.tools() == null ? List.of() : req.tools();

        int maxRounds = 5;
        for (int round = 0; round < maxRounds; round++) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model == null ? "qwen-max" : model);
            payload.put("stream", true);
            payload.put("messages", messages);
            // 首轮带 tools；续轮（已执行过工具）不带，让模型基于结果直接总结，避免再次触发
            if (round == 0 && !tools.isEmpty()) {
                payload.put("tools", toOpenAiTools(tools));
                payload.put("tool_choice", "auto");
            }
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest hr = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/$", "") + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> resp = http.send(hr, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("OpenAI chat 失败 HTTP " + resp.statusCode() + ": " + err);
            }
            RoundResult r = parseSse(resp.body(), onChunk);
            // 文本增量已在 parseSse 内逐帧下发（每收到一个 delta 即回调一次），此处不再整体下发
            if (r.toolCalls.isEmpty()) {
                onChunk.accept(ChatChunk.done());
                return;
            }
            // 有 tool_calls：本地执行，把 assistant(tool_calls) + tool(result) 加入 messages，续轮总结
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", r.content.isEmpty() ? null : r.content);
            assistantMsg.put("tool_calls", r.openAiToolCalls);
            messages.add(assistantMsg);
            for (ToolExec t : r.toolCalls) {
                ToolCall call = new ToolCall("openai-" + t.id, t.name, t.arguments);
                long toolStart = System.currentTimeMillis();
                ToolResult tr = toolExecutor.execute(call, req.username(), req.isAdmin());
                // N-13：所有工具（含 search_knowledge 等非 draft 工具）统一发审计帧，
                // 由 AiChatController 用本地 convId 落库；draft 帧只负责前端渲染，不再重复记账。
                ChatChunk auditFrame = ToolAuditFrame.of(toolStart, call, tr, objectMapper);
                if (auditFrame != null) {
                    onChunk.accept(auditFrame);
                }
                // 草稿类工具：把结构化预览作为 draft SSE 事件下发给前端（渲染「待确认表单」卡片）
                if (t.name != null && t.name.startsWith("draft_")) {
                    try {
                        String draftPayload = objectMapper.writeValueAsString(Map.of(
                                "toolName", t.name,
                                "argsJson", t.arguments,
                                "resultJson", tr.contentJson()));
                        onChunk.accept(ChatChunk.draft(draftPayload));
                    } catch (Exception ignored) {
                        // 草稿事件发送失败不影响主流程（模型仍会基于工具结果生成文字摘要）
                    }
                }
                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", t.id);
                toolMsg.put("content", tr.contentJson());
                messages.add(toolMsg);
            }
        }
        // 超过 maxRounds 仍未结束：以 done 收尾，避免前端挂起
        onChunk.accept(ChatChunk.done());
    }

    /** 解析一轮 SSE，逐帧下发文本增量，并聚合 tool_calls 分片（tool_calls 必须跨行拼接，故仍聚合） */
    private RoundResult parseSse(InputStream in, Consumer<ChatChunk> onChunk) throws Exception {
        RoundResult r = new RoundResult();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).strip();
            if ("[DONE]".equals(data)) {
                break;
            }
            JsonNode node = objectMapper.readTree(data);
            JsonNode choices = node.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                continue;
            }
            JsonNode delta = choices.get(0).get("delta");
            if (delta == null) {
                continue;
            }
            if (delta.has("content") && !delta.get("content").isNull() && !delta.get("content").asText().isEmpty()) {
                String t = delta.get("content").asText();
                r.content += t;
                onChunk.accept(ChatChunk.token(t));   // 逐帧下发，保证流式而非凑整段
            } else if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()
                    && !delta.get("reasoning_content").asText().isEmpty()) {
                // 推理模型（o1/deepseek-reasoner 等）将思考过程放在 reasoning_content，
                // 当 content 为空时回退读取，避免前端收到空回复（与 Coze 侧处理一致）。
                String t = delta.get("reasoning_content").asText();
                r.content += t;
                onChunk.accept(ChatChunk.token(t));   // 逐帧下发
            }
            JsonNode tcArr = delta.get("tool_calls");
            if (tcArr != null && tcArr.isArray()) {
                for (JsonNode tc : tcArr) {
                    int idx = tc.has("index") ? tc.get("index").asInt() : 0;
                    while (r.toolCalls.size() <= idx) {
                        r.toolCalls.add(new ToolExec());
                    }
                    ToolExec t = r.toolCalls.get(idx);
                    if (tc.has("id") && !tc.get("id").isNull()) {
                        t.id = tc.get("id").asText();
                    }
                    if (tc.has("function")) {
                        JsonNode fn = tc.get("function");
                        if (fn.has("name") && !fn.get("name").isNull()) {
                            t.name = fn.get("name").asText();
                        }
                        if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                            t.arguments += fn.get("arguments").asText();
                        }
                    }
                }
            }
        }
        // 组装 OpenAI 回传格式 tool_calls（assistant 消息需原样带回 id/type/function）
        for (ToolExec t : r.toolCalls) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id);
            m.put("type", "function");
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.name);
            fn.put("arguments", t.arguments.isEmpty() ? "{}" : t.arguments);
            m.put("function", fn);
            r.openAiToolCalls.add(m);
        }
        return r;
    }

    /** ToolRegistry 的 ToolSpec → OpenAI tools 数组 */
    private List<Map<String, Object>> toOpenAiTools(List<ToolSpec> specs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec s : specs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "function");
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", s.name());
            fn.put("description", s.description());
            try {
                fn.put("parameters", objectMapper.readTree(s.parametersJson()));
            } catch (Exception e) {
                fn.put("parameters", Map.of("type", "object", "properties", Map.of()));
            }
            m.put("function", fn);
            out.add(m);
        }
        return out;
    }

    /** 一轮聚合结果 */
    private static class RoundResult {
        String content = "";
        List<ToolExec> toolCalls = new ArrayList<>();
        List<Map<String, Object>> openAiToolCalls = new ArrayList<>();
    }

    /** 单个 tool_call 的流式聚合（按 index 拼接 name + arguments） */
    private static class ToolExec {
        String id = "call_" + System.nanoTime();
        String name = "";
        String arguments = "";
    }
}
