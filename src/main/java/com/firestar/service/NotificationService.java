package com.firestar.service;

import com.firestar.dto.NotificationVO;
import com.firestar.dto.NotifyCommand;
import com.firestar.dto.PublishNotificationRequest;
import com.firestar.dto.TemplateSaveRequest;
import com.firestar.dto.TemplateVO;
import com.firestar.entity.NotificationTemplate;

import java.util.List;

/**
 * 通知服务：业务侧唯一入口 + 用户侧收发 + 管理端发布。
 *
 * 【架构边界】业务代码只调 notify(NotifyCommand)，不认识 SSE、不建表、不写推送；
 * 推送由本 Service 发 RealtimeEvent(NOTIFY) 完成，网关（SseRealtimeGateway）订阅路由。
 *
 * 【事实表兜底】SSE 只是加速层：未读数永远以 sys_notification 的 COUNT 为准，
 * 断网期间到达的通知在重连后由前端调 unreadCount 校正，不丢。
 */
public interface NotificationService {

    // ===== 业务侧唯一入口 =====

    /**
     * 发通知（模板渲染 → 收件人 → dedup 落库 → SSE 推送）。
     * 全局开关关闭 / 模板停用 / 收件人为空 → 静默跳过（不抛）。
     */
    void notify(NotifyCommand cmd);

    // ===== 用户侧（登录即可，无权限点） =====

    /** 未读数（前端启动/重连后校正用，以事实表为准） */
    long unreadCount(Long userId);

    /**
     * 游标分页列表（倒序）。lastId 为空 = 从最新开始；返回 size 条或更少即到底。
     */
    List<NotificationVO> listForUser(Long userId, Long lastId, int size);

    /** 单条已读（幂等：已读不再写） */
    void markRead(Long userId, Long notificationId);

    /** 全部已读，返回本次置读条数 */
    int markAllRead(Long userId);

    // ===== 管理侧 =====

    /** 手动发布（P2 验收触发器），返回实际入库条数（dedup 去重后） */
    int publish(PublishNotificationRequest req, Long operatorId);

    /**
     * 记录检索（分页）：按类型 / 收件人 / 时间区间（管理页）。
     * 返回 VO 分页而非实体：前端「已读」列读 read 字段（实体只有 readAt，
     * 曾导致管理页永远显示未读，2026-10-20 虎哥截图实锤）；也不外泄
     * dedup_key / tenant_id / version 等内部字段。
     *
     * 【receiver 检索口径（2026-10-20 虎哥要求）】管理端查询条件原为用户 ID，
     * 管理员根本不知道 id 数值。改为传「用户名/昵称」关键字：纯数字按 ID 精确，
     * 其余按 sys_user.username / nickname 模糊匹配后转 id in 查询；
     * 关键字无匹配用户 → 直接返回空页（不降级为全量，避免误导）。
     */
    com.baomidou.mybatisplus.core.metadata.IPage<NotificationVO> listRecords(
            Integer pageNum, Integer pageSize, String type, String receiver,
            java.time.LocalDateTime from, java.time.LocalDateTime to);

    /** 模板列表（管理页） */
    List<NotificationTemplate> listTemplates();

    /** 保存模板文案/开关（改后新通知用新文案，历史不改写） */
    void updateTemplate(Long id, TemplateSaveRequest req);
}
