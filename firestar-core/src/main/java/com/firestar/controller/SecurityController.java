package com.firestar.controller;

import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.common.SecurityContextUtils;
import com.firestar.service.LoginProtectService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全运维接口（爆破防护闭环的「恢复」入口）。
 *
 * <p>解锁端点要求已登录，且仅 {@code ROLE_ADMIN} 可调用（{@link SecurityContextUtils#isAdmin}）。
 * 解锁动作本身会写 UNLOCKED 审计日志 + 推送安全告警，使闭环可追。
 * 路径不含 /api 前缀（代理层剥离）。
 */
@RestController
@RequestMapping("/system/security")
public class SecurityController {

    private final LoginProtectService protectService;

    public SecurityController(LoginProtectService protectService) {
        this.protectService = protectService;
    }

    @RequirePerm("log:unlock")
    @PostMapping("/unlock")
    public Result<Void> unlock(@RequestParam String username) {
        if (!SecurityContextUtils.isAdmin()) {
            return Result.error(403, "仅超级管理员可解锁账号");
        }
        String operator = SecurityContextUtils.currentUsername();
        protectService.unlock(username, operator);
        return Result.success();
    }
}
