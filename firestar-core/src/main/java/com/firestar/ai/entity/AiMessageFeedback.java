package com.firestar.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/** AI 回答赞踩（V217）：每个用户对每条 assistant 消息最多一条记录，再次点击同类型可取消。 */
@Getter
@Setter
@TableName(value = "ai_message_feedback")
public class AiMessageFeedback implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long messageId;

    private Long userId;

    /** LIKE | DISLIKE */
    private String feedbackType;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
