package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 问题反馈留言表（由 V32__feedback_and_help_center.sql 创建）。
 * 只增不改不删（closed 终态拒绝留言由 Service 层校验）。
 */
@Getter
@Setter
@TableName(value = "sys_feedback_message")
public class FeedbackMessage extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** → sys_feedback.id */
    private Long feedbackId;

    /** 留言人 → sys_user.id（创建人/接收人双向） */
    private Long senderId;

    /** 留言内容（富文本 HTML） */
    private String content;
}
