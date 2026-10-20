package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户侧通知列表项（铃铛面板）。
 *
 * 不返回 dedup_key / tenant_id / version 等内部字段——
 * 面板只需要"什么时候、什么事、去哪、读没读"。
 */
@Getter
@Setter
public class NotificationVO {

    private Long id;

    private String type;

    private String title;

    private String content;

    /** 级别：INFO / WARN / URGENT（前端分色） */
    private String level;

    private String linkUrl;

    private String bizType;

    private String bizId;

    /** 是否已读（前端灰显/加粗区分） */
    private Boolean read;

    /**
     * 收件人显示名「昵称（用户名）」（仅管理端 records 返回，用户侧不填）：
     * 展开明细直接给人名，不再甩裸 ID（2026-10-20 虎哥要求）。
     */
    private String receiverName;

    private LocalDateTime createdAt;
}
