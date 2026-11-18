package com.firestar.ai.dto;

/**
 * AI 对话请求（前端抽屉发送）。
 *
 * @param message        本轮用户输入
 * @param conversationId 续聊的本地会话 id（可空）。空 = 新开会话；非空 = 校验归属后在该会话内
 *                       回灌既有 user/assistant 历史（最多 20 条）再接本轮输入，实现多轮上下文。
 */
public record AiChatRequest(String message, Long conversationId) {
}
