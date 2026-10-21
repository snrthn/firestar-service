package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.entity.Feedback;
import com.firestar.entity.FeedbackMessage;
import com.firestar.entity.User;
import com.firestar.dto.NotifyCommand;
import com.firestar.mapper.FeedbackMapper;
import com.firestar.mapper.FeedbackMessageMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.FeedbackService;
import com.firestar.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问题反馈服务实现。
 *
 * 【状态机】字典 feedback_status：
 *   PENDING → PROCESSING → RESOLVED → CLOSED（主链）
 *   RESOLVED → PROCESSING（创建人重新打开）
 *   PENDING/PROCESSING/RESOLVED → CLOSED（直接关闭）
 *   CLOSED 为终态：不可留言、不可再流转。
 *
 * 【可见性】创建人 / 接收人 / ROLE_ADMIN 三者可见，其余一律 404 语义拒绝（防枚举 id）。
 * 【实时推送】复用现有 RealtimeEvent + SSE 网关，按 username 定向推相关方。
 */
@Service
public class FeedbackServiceImpl implements FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackServiceImpl.class);

    /** 合法状态集（与字典 feedback_status 对齐，新增字典值时同步此处与 transition 表） */
    private static final Set<String> STATUSES = Set.of("PENDING", "PROCESSING", "RESOLVED", "CLOSED");

    /** 合法流转表：key=当前状态 → 允许的目标状态 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "PENDING", Set.of("PROCESSING", "CLOSED"),
            "PROCESSING", Set.of("RESOLVED", "CLOSED"),
            "RESOLVED", Set.of("PROCESSING", "CLOSED")
            // CLOSED 为终态，无出边
    );

    private final FeedbackMapper feedbackMapper;
    private final FeedbackMessageMapper messageMapper;
    private final UserMapper userMapper;
    private final NotificationService notificationService;

    public FeedbackServiceImpl(FeedbackMapper feedbackMapper, FeedbackMessageMapper messageMapper,
                               UserMapper userMapper, NotificationService notificationService) {
        this.feedbackMapper = feedbackMapper;
        this.messageMapper = messageMapper;
        this.userMapper = userMapper;
        this.notificationService = notificationService;
    }

    @Override
    public Long create(Long creatorId, String title, String content, Long receiverId, String category) {
        if (title == null || title.isBlank()) {
            throw badRequest("请填写标题");
        }
        if (content == null || content.isBlank()) {
            throw badRequest("请填写问题描述");
        }
        if (receiverId == null) {
            throw badRequest("请选择接收人");
        }
        if (userMapper.selectById(receiverId) == null) {
            throw badRequest("接收人不存在");
        }
        Feedback fb = new Feedback();
        fb.setTitle(title);
        fb.setContent(content);
        fb.setReceiverId(receiverId);
        fb.setStatus("PENDING");
        // 类别走字典 feedback_category（与前端 DictSelect 同域值）；空值回落 OTHER，
        // 不校验合法性——字典可在管理端增删，硬校验会阻断业务（同 useDict 约定 4 的柔性处理）
        fb.setCategory(category == null || category.isBlank() ? "OTHER" : category.trim());
        fb.setCreatedBy(creatorId);
        feedbackMapper.insert(fb);

        // 只通知接收人（接收人 = 创建人时不自扰，避免同一个人收到两条一样的内容；
        // 走通知中枢落库 → 铃铛徽标/面板/NOTIFY 推送统一生效，2026-10-21）
        if (!receiverId.equals(creatorId)) {
            notifyUser(receiverId, fb.getId(), "新问题反馈：" + fb.getTitle(),
                    displayName(creatorId) + " 提交了问题反馈，请及时处理。", "CREATED", creatorId);
        }
        log.info("[feedback] #{} 创建（接收人 {}）", fb.getId(), receiverId);
        return fb.getId();
    }

    @Override
    public IPage<Feedback> pageMy(Long creatorId, String status, String category, long pageNum, long pageSize) {
        LambdaQueryWrapper<Feedback> qw = new LambdaQueryWrapper<Feedback>()
                .eq(Feedback::getCreatedBy, creatorId)
                .eq(status != null && !status.isBlank(), Feedback::getStatus, status)
                .eq(category != null && !category.isBlank(), Feedback::getCategory, category)
                .orderByDesc(Feedback::getId);
        return feedbackMapper.selectPage(new Page<>(pageNum, pageSize), qw);
    }

    @Override
    public IPage<Feedback> pageReceived(Long receiverId, String status, String category, long pageNum, long pageSize) {
        LambdaQueryWrapper<Feedback> qw = new LambdaQueryWrapper<Feedback>()
                .eq(Feedback::getReceiverId, receiverId)
                .eq(status != null && !status.isBlank(), Feedback::getStatus, status)
                .eq(category != null && !category.isBlank(), Feedback::getCategory, category)
                .orderByDesc(Feedback::getId);
        return feedbackMapper.selectPage(new Page<>(pageNum, pageSize), qw);
    }

    @Override
    public Map<String, Object> detail(Long viewerId, Long feedbackId, boolean isAdmin) {
        Feedback fb = requireVisible(viewerId, feedbackId, isAdmin);
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", fb.getId());
        vo.put("title", fb.getTitle());
        vo.put("content", fb.getContent());
        vo.put("status", fb.getStatus());
        vo.put("category", fb.getCategory());
        vo.put("receiverId", fb.getReceiverId());
        vo.put("createdAt", fb.getCreatedAt());
        vo.put("updatedAt", fb.getUpdatedAt());
        vo.put("creator", displayName(fb.getCreatedBy()));
        vo.put("receiver", displayName(fb.getReceiverId()));
        vo.put("isCreator", viewerId.equals(fb.getCreatedBy()));
        return vo;
    }

    @Override
    public Long addMessage(Long senderId, Long feedbackId, String content, boolean isAdmin) {
        Feedback fb = requireVisible(senderId, feedbackId, isAdmin);
        if ("CLOSED".equals(fb.getStatus())) {
            throw badRequest("反馈已关闭，不能再留言");
        }
        FeedbackMessage msg = new FeedbackMessage();
        msg.setFeedbackId(feedbackId);
        msg.setSenderId(senderId);
        msg.setContent(content);
        msg.setCreatedBy(senderId);
        messageMapper.insert(msg);

        // PENDING 有留言即进入 PROCESSING（接收人首条留言才算处理中，创建人留言不改变状态）
        if ("PENDING".equals(fb.getStatus()) && senderId.equals(fb.getReceiverId())) {
            fb.setStatus("PROCESSING");
            feedbackMapper.updateById(fb);
            // 状态变更只通知对方（创建人），不给留言人自己发
            notifyUser(fb.getCreatedBy(), fb.getId(), "反馈开始处理：" + fb.getTitle(),
                    displayName(senderId) + " 已开始处理该反馈。",
                    "STS:PROCESSING:" + msg.getId(), senderId);
        }

        // 留言只通知对方（发送人自己不用通知；对方 = 创建人/接收人中非发送人的那一方）
        Long other = senderId.equals(fb.getCreatedBy()) ? fb.getReceiverId() : fb.getCreatedBy();
        if (!other.equals(senderId)) {
            notifyUser(other, fb.getId(), "反馈有新留言：" + fb.getTitle(),
                    displayName(senderId) + " 在反馈中留言，点击查看。", "MSG:" + msg.getId(), senderId);
        }
        return msg.getId();
    }

    @Override
    public void changeStatus(Long operatorId, Long feedbackId, String targetStatus, boolean isAdmin) {
        if (!STATUSES.contains(targetStatus)) {
            throw badRequest("非法状态：" + targetStatus);
        }
        Feedback fb = requireVisible(operatorId, feedbackId, isAdmin);
        String current = fb.getStatus();
        if ("CLOSED".equals(current)) {
            throw badRequest("反馈已关闭，状态不可再变更");
        }
        Set<String> allowed = TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(targetStatus)) {
            throw badRequest("不允许从 " + current + " 流转到 " + targetStatus);
        }
        // 角色约束：RESOLVED/PROCESSING 只能由接收人（或 admin）操作；
        // CLOSE 谁都可以（创建人放弃 / 接收人办结）；重新打开只能创建人（或 admin）。
        if ("PROCESSING".equals(targetStatus) && !isAdmin && !operatorId.equals(fb.getReceiverId())) {
            throw badRequest("只有接收人可以开始处理");
        }
        if ("RESOLVED".equals(targetStatus) && !isAdmin && !operatorId.equals(fb.getReceiverId())) {
            throw badRequest("只有接收人可以标记解决");
        }
        if ("PROCESSING".equals(targetStatus) && "RESOLVED".equals(current)
                && !isAdmin && !operatorId.equals(fb.getCreatedBy())) {
            throw badRequest("只有提交人可以重新打开");
        }
        fb.setStatus(targetStatus);
        feedbackMapper.updateById(fb);
        // 状态变更只通知对方（操作人自己不收；自反馈场景无人可通知则静默跳过）
        Long counterpart = operatorId.equals(fb.getCreatedBy()) ? fb.getReceiverId() : fb.getCreatedBy();
        if (!counterpart.equals(operatorId)) {
            notifyUser(counterpart, fb.getId(), "反馈状态更新：" + fb.getTitle(),
                    "状态由 " + statusLabel(current) + " 变更为 " + statusLabel(targetStatus) + "。",
                    "STS:" + targetStatus + ":" + System.currentTimeMillis(), operatorId);
        }
        log.info("[feedback] #{} 状态 {} → {}（操作人 {}）", feedbackId, current, targetStatus, operatorId);
    }

    @Override
    public List<Map<String, Object>> messages(Long viewerId, Long feedbackId, boolean isAdmin) {
        requireVisible(viewerId, feedbackId, isAdmin);
        return messageMapper.selectList(new LambdaQueryWrapper<FeedbackMessage>()
                        .eq(FeedbackMessage::getFeedbackId, feedbackId)
                        .orderByAsc(FeedbackMessage::getId))
                .stream()
                .map(m -> {
                    Map<String, Object> vo = new LinkedHashMap<>();
                    vo.put("id", m.getId());
                    vo.put("content", m.getContent());
                    vo.put("createdAt", m.getCreatedAt());
                    vo.put("sender", displayName(m.getSenderId()));
                    vo.put("isSelf", viewerId.equals(m.getSenderId()));
                    return vo;
                })
                .toList();
    }

    @Override
    public List<Map<String, Object>> receiverOptions() {
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .eq(User::getStatus, "ENABLED")
                        .orderByAsc(User::getId)
                        .select(User::getId, User::getUsername, User::getNickname))
                .stream()
                .map(u -> {
                    Map<String, Object> vo = new LinkedHashMap<>();
                    vo.put("id", u.getId());
                    vo.put("username", u.getUsername());
                    vo.put("nickname", u.getNickname());
                    return vo;
                })
                .toList();
    }

    // ===== 私有辅助 =====

    /** 可见性校验：创建人/接收人/admin 之外一律 404（防 id 枚举） */
    private Feedback requireVisible(Long viewerId, Long feedbackId, boolean isAdmin) {
        Feedback fb = feedbackMapper.selectById(feedbackId);
        if (fb == null || (!isAdmin && !viewerId.equals(fb.getCreatedBy())
                && !viewerId.equals(fb.getReceiverId()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "反馈不存在或无权访问");
        }
        return fb;
    }

    private String displayName(Long userId) {
        if (userId == null) return null;
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getId, userId)
                .select(User::getNickname, User::getUsername));
        return u == null ? String.valueOf(userId)
                : (u.getNickname() != null && !u.getNickname().isBlank() ? u.getNickname() : u.getUsername());
    }

    /**
     * 反馈事件 → 通知中枢（sys_notification 落库 + NOTIFY SSE 推送）。
     * 走 SYSTEM_NOTICE 模板（{{title}}/{{content}} 变量），bizType=FEEDBACK，
     * linkUrl=/system/feedback?tab=my|received 供详情弹窗「查看详情」直达，
     * 并按收件人与该反馈的关系精确带 Tab（2026-10-21 虎哥要求：通知跳反馈页时
     * 自动落到对应 Tab）：收件人是创建人 → my（我提交的），否则是接收人 →
     * received（我接收的）；反馈查不到时退化为 my（与旧行为一致）。
     * dedupKey 前缀 FB:{feedbackId}:{kind}，同一事件重试不落第二条。
     */
    private void notifyUser(Long userId, Long feedbackId, String title, String content,
                            String kind, Long operatorId) {
        if (userId == null) return;
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("SYSTEM_NOTICE");
        cmd.setReceivers(List.of(userId));
        cmd.setVars(Map.of("title", title, "content", content));
        cmd.setBizType("FEEDBACK");
        cmd.setBizId(String.valueOf(feedbackId));
        // linkUrl 按收件人与反馈的关系精确带 Tab 参数（4 个调用点——新反馈/开始处理/
        // 新留言/状态更新——都走本方法，此处统一判断，各调用点无需重复算）
        Feedback fb = feedbackMapper.selectById(feedbackId);
        String tab = (fb != null && !userId.equals(fb.getCreatedBy())) ? "received" : "my";
        cmd.setLinkUrl("/system/feedback?tab=" + tab);
        cmd.setDedupKey("FB:" + feedbackId + ":" + kind);
        cmd.setOperatorId(operatorId);
        notificationService.notify(cmd);
    }

    /** 状态中文名（通知文案用；与字典 feedback_status 的 label 对齐） */
    private String statusLabel(String status) {
        return switch (status) {
            case "PENDING" -> "待处理";
            case "PROCESSING" -> "处理中";
            case "RESOLVED" -> "已解决";
            case "CLOSED" -> "已关闭";
            default -> status;
        };
    }

    private ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
