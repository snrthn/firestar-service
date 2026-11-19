package com.firestar.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 助手消息（ai_messages，V100 新增）。每轮 user / assistant / tool 一行，
 * 含 token 用量与耗时，便于成本核算与审计回放。
 */
@Getter
@Setter
@TableName(value = "ai_messages")
public class AiMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** user | assistant | tool */
    private String role;

    private String content;

    /** 思考过程文本（推理模型 reasoning_content 聚合；NULL = 无思考过程），仅展示用，不回灌上下文 */
    private String reasoningText;

    private Integer tokensPrompt;

    private Integer tokensCompletion;

    private Integer latencyMs;

    private String modelVersion;

    /** 本轮请求的 request_id（SSE meta 帧下发，排障用），V217 新增 */
    private String requestId;

    private LocalDateTime createdAt;
}
