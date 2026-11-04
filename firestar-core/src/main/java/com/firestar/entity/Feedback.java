package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 问题反馈主表（由 V32__feedback_and_help_center.sql 创建）。
 * 改表请走 Flyway V{n} 增量脚本，然后同步本文件。
 */
@Getter
@Setter
@TableName(value = "sys_feedback")
public class Feedback extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 标题 */
    private String title;

    /** 富文本正文（Tiptap HTML） */
    private String content;

    /** 指定接收人 → sys_user.id（全员可选） */
    private Long receiverId;

    /** 状态（字典 feedback_status：PENDING/PROCESSING/RESOLVED/CLOSED） */
    private String status;

    /** 问题类别（字典 feedback_category，V34 新增；默认 OTHER） */
    private String category;
}
