package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.entity.Feedback;
import com.firestar.entity.FeedbackMessage;

import java.util.List;
import java.util.Map;

/**
 * 问题反馈服务。
 * 状态字典 feedback_status：PENDING → PROCESSING → RESOLVED → CLOSED。
 */
public interface FeedbackService {

    /** 创建反馈（receiverId 必填，全员可选；category 为空回落 OTHER） */
    Long create(Long creatorId, String title, String content, Long receiverId, String category);

    /** 我提交的（分页，状态/类别可筛选） */
    IPage<Feedback> pageMy(Long creatorId, String status, String category, long pageNum, long pageSize);

    /** 我接收的（分页，状态/类别可筛选） */
    IPage<Feedback> pageReceived(Long receiverId, String status, String category, long pageNum, long pageSize);

    /** 详情（仅创建人/接收人/admin 可见；带创建人与接收人展示名） */
    Map<String, Object> detail(Long viewerId, Long feedbackId, boolean isAdmin);

    /** 追加留言（closed 终态拒绝；仅相关方） */
    Long addMessage(Long senderId, Long feedbackId, String content, boolean isAdmin);

    /** 状态流转（校验合法迁移 + 操作角色） */
    void changeStatus(Long operatorId, Long feedbackId, String targetStatus, boolean isAdmin);

    /** 某反馈的留言时间线（按时间正序，带留言人展示名） */
    List<Map<String, Object>> messages(Long viewerId, Long feedbackId, boolean isAdmin);

    /** 可选接收人：全部启用状态用户（id/username/nickname） */
    List<Map<String, Object>> receiverOptions();
}
