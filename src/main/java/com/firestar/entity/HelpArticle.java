package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 帮助中心文章表（由 V32__feedback_and_help_center.sql 创建）。
 * 改表请走 Flyway V{n} 增量脚本，然后同步本文件。
 */
@Getter
@Setter
@TableName(value = "sys_help_article")
public class HelpArticle extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 标题 */
    private String title;

    /** 富文本正文（Tiptap HTML） */
    private String content;

    /** 分类（阅读页左侧锚点分组） */
    private String category;

    /** 排序号（小在前） */
    private Integer sortNo;

    /** 状态（字典 common_status：ENABLED/DISABLED） */
    private String status;
}
