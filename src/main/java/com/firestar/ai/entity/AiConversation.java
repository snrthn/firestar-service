package com.firestar.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 助手会话（ai_conversations，V100 新增）。
 * 扣子运行日志仅保留 7 天，不满足审计，故会话落自有库。
 * externalSessionId 记录扣子 conversation_id，供插件回调反查当前登录用户。
 */
@Getter
@Setter
@TableName(value = "ai_conversations")
public class AiConversation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    /** 本次实际使用的供应商 */
    private String provider;

    /** 扣子 conversation_id 等 */
    private String externalSessionId;

    /** 1进行中 2已结束 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
