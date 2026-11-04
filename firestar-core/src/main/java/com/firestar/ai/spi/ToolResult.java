package com.firestar.ai.spi;

/** 工具执行结果（回给供应商；已是经 EgressMasker 脱敏后的内容）。 */
public record ToolResult(String callId, String name, String contentJson, boolean isError) {
}
