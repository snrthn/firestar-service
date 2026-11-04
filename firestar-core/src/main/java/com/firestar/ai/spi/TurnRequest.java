package com.firestar.ai.spi;

import java.util.List;

/** 一轮对话请求（供应商无关）。 */
public record TurnRequest(
        String conversationId,
        List<ChatMessage> messages,
        List<ToolSpec> tools,
        ChatOptions options,
        /** 发起用户的登录名（工具执行身份，绕过 CompletableFuture 跨线程丢失 SecurityContext） */
        String username,
        /** 是否超级管理员：L2 看板工具门禁依赖此信号 */
        boolean isAdmin) {
}
