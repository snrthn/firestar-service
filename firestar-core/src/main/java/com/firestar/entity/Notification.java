package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 通知事实表：一人一条，便于独立已读。
 * 由 V30__p2_notification_module.sql 创建；改表请走 Flyway V{n} 增量脚本，然后同步本文件。
 *
 * 【只增不改】除 read_at / read_channel（已读）外，任何字段不更新——
 * 通知是"送达证据"，改了内容就不是同一条通知了。delFlag 也没有：
 * 过期清理靠 expire_at + P3 定时任务，不做逻辑删。
 *
 * 【dedup】dedup_key 唯一键 + NotificationMapper#insertIgnore 保证
 * 并发/重试不落重复（先查后插有竞态，不用）。
 */
@Getter
@Setter
@TableName(value = "sys_notification", autoResultMap = true)
public class Notification extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 收件人 → sys_user.id */
    private Long receiverId;

    /** 通知类型：APPROVAL_TODO / SYSTEM_NOTICE ... */
    private String type;

    /** 标题（模板渲染后的成品） */
    private String title;

    /** 正文（模板渲染后的成品） */
    private String content;

    /** 关联业务类型（可选） */
    private String bizType;

    /** 关联业务 ID（可选，字符串以兼容非数字主键） */
    private String bizId;

    /** 点击跳转（相对路径） */
    private String linkUrl;

    /** 级别：INFO / WARN / URGENT */
    private String level;

    /** 已读时间（NULL = 未读） */
    private LocalDateTime readAt;

    /** 已读渠道：INAPP */
    private String readChannel;

    /** 去重键：{type}:{bizType}:{bizId}:{receiverId} 或调用方显式指定 */
    private String dedupKey;

    /** 过期时间（NULL = 不过期；P3 清理任务按此字段扫） */
    private LocalDateTime expireAt;
}
