package com.firestar.ai.spi;

/** 工具调用请求（来自供应商：扣子经插件回调、OpenAI 经 tool_calls）。 */
public record ToolCall(String id, String name, String argumentsJson) {
}
