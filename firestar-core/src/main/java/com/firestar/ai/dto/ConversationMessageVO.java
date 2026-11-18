package com.firestar.ai.dto;

import java.time.LocalDateTime;

/**
 * 会话回放消息（前端历史回放渲染）。
 * role ∈ user | assistant | tool；tool 行 content 是工具结果 JSON，
 * toolName 用于前端把 draft_shipment_create 结果渲染成业务卡（发货单草稿）。
 */
public record ConversationMessageVO(
        Long id,
        String role,
        String content,
        String toolName,
        String modelVersion,
        Integer latencyMs,
        LocalDateTime createdAt) {
}
