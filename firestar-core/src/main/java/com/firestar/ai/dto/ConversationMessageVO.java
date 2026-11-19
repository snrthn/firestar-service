package com.firestar.ai.dto;

import java.time.LocalDateTime;

/**
 * 会话回放消息（前端历史回放渲染）。
 * role ∈ user | assistant | tool；tool 行 content 是工具结果 JSON，
 * toolName 用于前端把 draft_shipment_create 结果渲染成业务卡（发货单草稿）；
 * reasoningText 为思考过程文本（assistant 行，无则 null），前端折叠展示；
 * requestId 为请求 id；myFeedback 为当前用户对该消息的 LIKE/DISLIKE（无则 null）。
 */
public record ConversationMessageVO(
        Long id,
        String role,
        String content,
        String reasoningText,
        String requestId,
        String myFeedback,
        String toolName,
        String modelVersion,
        Integer latencyMs,
        LocalDateTime createdAt) {
}
