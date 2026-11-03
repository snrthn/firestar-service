package com.firestar.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 助手工具调用审计（ai_tool_calls，V100 新增）。
 * 每次工具调用的入参 / 结果全量留痕：谁、何时、调了哪个工具、返回几行、耗时、是否出错。
 */
@Getter
@Setter
@TableName(value = "ai_tool_calls")
public class AiToolCall implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long messageId;

    private Long conversationId;

    private String toolName;

    private String argumentsJson;

    private String resultJson;

    private Integer rowsReturned;

    private Integer isError;

    private Integer latencyMs;

    private LocalDateTime createdAt;
}
