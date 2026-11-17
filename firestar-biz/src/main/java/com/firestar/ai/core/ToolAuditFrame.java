package com.firestar.ai.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ToolCall;
import com.firestar.ai.spi.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用审计帧构造器（N-13：补齐 OpenAI / Anthropic 直连模式的工具审计）。
 *
 * <p>直连 provider 执行完工具后，用本类发一帧 {@code ChatChunk.tool_call}；
 * AiChatController 持有本地 convId，在 SSE 消费分支统一落 ai_tool_calls 表。
 * 这样 provider 不需要知道本地会话 id（它只有外部 session id），也不破坏 ToolExecutor 契约。</p>
 *
 * <p>draft_ 工具同样会发本帧（审计全量），另发 draft 帧（渲染表单）；
 * Controller 侧 draft 分支不再重复记账，避免双写。</p>
 */
public final class ToolAuditFrame {

    private ToolAuditFrame() {
    }

    /**
     * 构造工具调用审计帧。序列化失败时返回 null，调用方跳过即可（审计失败不影响主链路）。
     *
     * @param startMs 工具执行开始时间（System.currentTimeMillis()）
     * @param call    工具调用入参
     * @param result  工具执行结果
     * @param mapper  JSON 序列化器
     */
    public static ChatChunk of(long startMs, ToolCall call, ToolResult result, ObjectMapper mapper) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", call.name());
        payload.put("argsJson", call.argumentsJson());
        payload.put("resultJson", result == null ? null : result.contentJson());
        payload.put("isError", result != null && result.isError());
        payload.put("latencyMs", System.currentTimeMillis() - startMs);
        try {
            return ChatChunk.toolCall(mapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 审计帧解析结果（供 AiChatController 落库）。 */
    public record Data(String toolName, String argsJson, String resultJson,
                       boolean isError, long latencyMs) {
    }

    /**
     * 从 tool_call 帧解析审计数据。解析失败返回 null，调用方跳过即可。
     */
    public static Data parse(ChatChunk chunk, ObjectMapper mapper) {
        if (chunk == null || !"tool_call".equals(chunk.type()) || chunk.data() == null) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode n = mapper.readTree(chunk.data());
            String name = n.path("toolName").asText(null);
            if (name == null) {
                return null;
            }
            return new Data(name,
                    n.path("argsJson").asText("{}"),
                    n.path("resultJson").asText("{}"),
                    n.path("isError").asBoolean(false),
                    n.path("latencyMs").asLong(0));
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
