package com.firestar.ai.spi;

/**
 * 流式输出块（供应商无关）。
 * type ∈ token(文本增量) | done(结束) | error(错误) | fallback(降级兜底) | tool_call(工具调用，未来 OpenAI 用)。
 */
public record ChatChunk(String type, String text, String data) {
    public static ChatChunk token(String t) {
        return new ChatChunk("token", t, null);
    }

    public static ChatChunk done() {
        return new ChatChunk("done", null, null);
    }

    public static ChatChunk error(String msg) {
        return new ChatChunk("error", msg, null);
    }

    /** 降级兜底帧：前端据此把抽屉切到普通搜索 / 报错形态 */
    public static ChatChunk fallback(String mode, String reason) {
        return new ChatChunk("fallback", mode, reason);
    }

    /** 流起始元数据帧：携带 conversationId，供前端事后按会话拉取草稿等 */
    public static ChatChunk meta(String payloadJson) {
        return new ChatChunk("meta", null, payloadJson);
    }

    /** 草稿预览帧：携带 draft 工具的预览结果（结构化 JSON），前端据此渲染「待确认表单」卡片 */
    public static ChatChunk draft(String payloadJson) {
        return new ChatChunk("draft", null, payloadJson);
    }

    public static ChatChunk toolCall(String name, String argumentsJson) {
        return new ChatChunk("tool_call", name, argumentsJson);
    }
}
