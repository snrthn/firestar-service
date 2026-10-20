package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.entity.Feedback;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.service.FeedbackService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 问题反馈接口（虎哥 2026-10-20 拍板：全员功能，header 入口）。
 *
 * 【路径约定】后端路由不带 /api；除白名单外默认需 JWT。
 * 【权限】列表/详情/留言/状态 = 登录即可（页面菜单已授权全员，perm=feedback:list 仅供
 *         菜单树挂载与前端守卫，接口层不做 @RequirePerm，避免收件人视角被权限点误伤）；
 *         接收人下拉 = 登录即可（全员可选，不要求 rbac:user）。
 * 【实时】创建/留言/状态变更走通知中枢（sys_notification 落库 + NOTIFY SSE），
 *         只通知对方、不给操作人自己发（2026-10-21 修重复推送）。
 */
@RestController
@RequestMapping("/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final UserMapper userMapper;

    public FeedbackController(FeedbackService feedbackService, UserMapper userMapper) {
        this.feedbackService = feedbackService;
        this.userMapper = userMapper;
    }

    /** 可选接收人：全部启用用户（全员可被指定） */
    @GetMapping("/receivers")
    public Result<List<Map<String, Object>>> receivers() {
        return Result.success(feedbackService.receiverOptions());
    }

    /** 创建反馈 */
    @PostMapping
    public Result<Long> create(@RequestBody CreateRequest req) {
        return Result.success(feedbackService.create(
                currentUserId(), req.getTitle(), req.getContent(), req.getReceiverId(), req.getCategory()));
    }

    /** 我提交的 */
    @GetMapping("/my")
    public Result<IPage<Feedback>> my(@RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "10") long pageSize,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String category) {
        return Result.success(feedbackService.pageMy(currentUserId(), status, category, pageNum, pageSize));
    }

    /** 我接收的 */
    @GetMapping("/received")
    public Result<IPage<Feedback>> received(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String category) {
        return Result.success(feedbackService.pageReceived(currentUserId(), status, category, pageNum, pageSize));
    }

    /** 详情（创建人/接收人/admin 可见） */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.success(feedbackService.detail(currentUserId(), id, isAdmin()));
    }

    /** 留言时间线（正序） */
    @GetMapping("/{id}/messages")
    public Result<List<Map<String, Object>>> messages(@PathVariable Long id) {
        return Result.success(feedbackService.messages(currentUserId(), id, isAdmin()));
    }

    /** 追加留言（closed 终态拒绝） */
    @PostMapping("/{id}/messages")
    public Result<Long> addMessage(@PathVariable Long id, @RequestBody MessageRequest req) {
        return Result.success(feedbackService.addMessage(currentUserId(), id, req.getContent(), isAdmin()));
    }

    /** 状态流转 */
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        feedbackService.changeStatus(currentUserId(), id, req.getStatus(), isAdmin());
        return Result.success();
    }

    /** 管理端占位：预留（当前无管理侧特殊接口，perm_code feedback:list 仅供菜单挂载） */
    @RequirePerm("feedback:list")
    @GetMapping("/perm-probe")
    public Result<Void> permProbe() {
        return Result.success();
    }

    // ===== 上下文辅助 =====

    private Long currentUserId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).select(User::getId));
        if (u == null) {
            throw new IllegalStateException("当前登录用户不存在");
        }
        return u.getId();
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    // ===== 请求体 =====

    @Getter
    @Setter
    public static class CreateRequest {
        private String title;
        private String content;
        private Long receiverId;
        /** 问题类别（字典 feedback_category；空则后端回落 OTHER） */
        private String category;
    }

    @Getter
    @Setter
    public static class MessageRequest {
        private String content;
    }

    @Getter
    @Setter
    public static class StatusRequest {
        private String status;
    }
}
