package com.firestar.ai.dto;

import java.time.LocalDateTime;

/** 历史会话列表项（前端「最近会话」面板）。不含 userId / externalSessionId 等内部字段。 */
public record ConversationVO(
        Long id,
        String title,
        String provider,
        /** 1进行中 2已结束 */
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
