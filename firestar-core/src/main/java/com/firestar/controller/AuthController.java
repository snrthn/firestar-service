package com.firestar.controller;

import com.firestar.common.IpUtil;
import com.firestar.common.Result;
import com.firestar.dto.ChangePasswordRequest;
import com.firestar.dto.LoginRequest;
import com.firestar.dto.LoginResponse;
import com.firestar.dto.MenuVO;
import com.firestar.dto.ProfileUpdateRequest;
import com.firestar.dto.ProfileVO;
import com.firestar.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证模块接口。
 *
 * 【路径约定】后端路由不带 /api（代理层剥离）；前端打 /api/auth/xxx，代理转发为 /auth/xxx。
 * 白名单：/auth/login 公开；logout / profile / password / menus 需携带有效 JWT。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest req, HttpServletRequest request) {
        // 真实 IP 由反代头推导（直连时退化为 remoteAddr），登录成功写入 last_login_ip
        return Result.success(authService.login(req, IpUtil.getClientIp(request), request.getHeader("User-Agent")));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout(extractToken(request));
        return Result.success();
    }

    @GetMapping("/profile")
    public Result<ProfileVO> profile() {
        return Result.success(authService.profile());
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest req) {
        authService.changePassword(req);
        return Result.success();
    }

    @PutMapping("/profile")
    public Result<Void> updateProfile(@RequestBody ProfileUpdateRequest req) {
        authService.updateProfile(req);
        return Result.success();
    }

    @GetMapping("/menus")
    public Result<List<MenuVO>> menus() {
        return Result.success(authService.menus());
    }

    /**
     * 会话探活（前端心跳每 5s 调一次）：本身不做任何事，能穿过
     * JwtAuthenticationFilter 到这里即代表会话有效；被顶号（jti 不匹配）/
     * 黑名单/过期时 filter 直接 401，前端据此弹「重新登录」模态框。
     * 与 profile 等同属 auth 自服务，只验 JWT、不需要 @RequirePerm。
     */
    @GetMapping("/session-check")
    public Result<Void> sessionCheck() {
        return Result.success();
    }

    private String extractToken(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            return h.substring(7);
        }
        return null;
    }
}
