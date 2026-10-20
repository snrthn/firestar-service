package com.firestar.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.common.Result;
import com.firestar.dto.NotificationVO;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.service.NotificationService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户侧通知接口（铃铛面板数据源）。
 *
 * 【路径约定】同其它业务 Controller：后端路由不带 /api；除登录页外默认需 JWT，
 * SecurityConfig 白名单未放行本路径，天然受保护。
 *
 * 【权限】全部「登录即可，无 @RequirePerm」——通知是全登录用户功能，
 * 每人只看自己的（receiver_id 由服务端从登录态取，不信任客户端传参）。
 * 用户侧不做独立页面/路由：header 铃铛 + 下拉面板即终点（P2 方案 4.4）。
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final UserMapper userMapper;

    public NotificationController(NotificationService notificationService, UserMapper userMapper) {
        this.notificationService = notificationService;
        this.userMapper = userMapper;
    }

    /** 未读数：前端启动 / SSE 重连后校正用（事实表 COUNT，断网不丢） */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(notificationService.unreadCount(currentUserId()));
    }

    /** 游标分页列表（倒序）。lastId 为空 = 从最新开始；size 默认 10、最大 50 */
    @GetMapping
    public Result<List<NotificationVO>> list(@RequestParam(required = false) Long lastId,
                                             @RequestParam(defaultValue = "10") int size) {
        return Result.success(notificationService.listForUser(currentUserId(), lastId, size));
    }

    /** 单条已读（幂等；已读再点不报错） */
    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        notificationService.markRead(currentUserId(), id);
        return Result.success();
    }

    /** 全部已读 */
    @PostMapping("/read-all")
    public Result<Map<String, Object>> readAll() {
        int updated = notificationService.markAllRead(currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updated", updated);
        return Result.success(data);
    }

    private Long currentUserId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).select(User::getId));
        if (u == null) {
            throw new IllegalStateException("当前登录用户不存在");
        }
        return u.getId();
    }
}
