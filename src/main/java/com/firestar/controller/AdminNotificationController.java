package com.firestar.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.NotificationVO;
import com.firestar.dto.PublishNotificationRequest;
import com.firestar.dto.TemplateSaveRequest;
import com.firestar.entity.NotificationTemplate;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.service.NotificationService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理侧通知接口（系统管理菜单：通知模板 / 通知记录）。
 *
 * 【菜单挂载】V30 种子：id=32 通知模板（notify:template）、id=33 通知记录（notify:manage），
 * 挂系统管理(id=12)下 sort_no 9/10；ROLE_ADMIN 已授权。未授权角色侧栏不可见、
 * URL 直达落 403（RBAC 动态路由 + @RequirePerm 双闸）。
 *
 * 【publish = P2 验收触发器】管理端手动发一条 → 目标账号铃铛出角标（SSE 实时 +1），
 * 断网重连后未读数以事实表校正（P2 方案第六节验收清单）。
 */
@RestController
@RequestMapping("/admin/notifications")
public class AdminNotificationController {

    private final NotificationService notificationService;
    private final UserMapper userMapper;

    public AdminNotificationController(NotificationService notificationService, UserMapper userMapper) {
        this.notificationService = notificationService;
        this.userMapper = userMapper;
    }

    /** 手动发布：type/标题/正文/链接/级别/接收人多选（P2 验收操作入口） */
    @RequirePerm("notify:publish")
    @PostMapping("/publish")
    public Result<Map<String, Object>> publish(@RequestBody PublishNotificationRequest req) {
        int inserted;
        try {
            inserted = notificationService.publish(req, currentUserId());
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 业务拒绝（开关关闭 / 收件人为空 / 模板停用）转 400 + 明确文案，
            // 不落 GlobalExceptionHandler 的通用 500「服务器内部错误」
            return Result.error(400, e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inserted", inserted);
        return Result.success(data);
    }

    /** 记录查询（分页）：按类型 / 收件人（用户名或昵称）/ 时间区间；返回 VO 分页（含 read 已读态） */
    @RequirePerm("notify:manage")
    @GetMapping
    public Result<com.baomidou.mybatisplus.core.metadata.IPage<NotificationVO>> records(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String receiver,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to) {
        return Result.success(
                notificationService.listRecords(pageNum, pageSize, type, receiver, from, to));
    }

    /** 模板列表（下拉全量） */
    @RequirePerm("notify:template")
    @GetMapping("/templates")
    public Result<List<NotificationTemplate>> templates() {
        return Result.success(notificationService.listTemplates());
    }

    /** 模板分页检索（列表页：编码/模板名称模糊筛选 + 分页，通用查询页） */
    @RequirePerm("notify:template")
    @GetMapping("/templates/page")
    public Result<com.baomidou.mybatisplus.core.metadata.IPage<NotificationTemplate>> pageTemplates(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String templateName) {
        return Result.success(notificationService.pageTemplates(pageNum, pageSize, code, templateName));
    }

    /** 保存模板文案/开关（改后新通知用新文案，历史不改写） */
    @RequirePerm("notify:template:edit")
    @PutMapping("/templates/{id}")
    public Result<Void> saveTemplate(@PathVariable Long id, @RequestBody TemplateSaveRequest req) {
        try {
            notificationService.updateTemplate(id, req);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
        return Result.success();
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
