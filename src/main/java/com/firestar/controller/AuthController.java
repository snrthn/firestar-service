package com.firestar.controller;

import com.firestar.common.IpUtil;
import com.firestar.common.JwtUtil;
import com.firestar.common.Result;
import com.firestar.dto.ChangePasswordRequest;
import com.firestar.dto.LoginRequest;
import com.firestar.dto.LoginResponse;
import com.firestar.dto.MenuVO;
import com.firestar.dto.ProfileUpdateRequest;
import com.firestar.dto.ProfileVO;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.realtime.RealtimeGateway;
import com.firestar.service.AuthService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final RealtimeGateway realtimeGateway;

    public AuthController(AuthService authService, JwtUtil jwtUtil, UserMapper userMapper,
                          RealtimeGateway realtimeGateway) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
        this.realtimeGateway = realtimeGateway;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest req, HttpServletRequest request) {
        // 真实 IP 由反代头推导（直连时退化为 remoteAddr），登录成功写入 last_login_ip
        return Result.success(authService.login(req, IpUtil.getClientIp(request)));
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

    /**
     * SSE 实时推送流端点。握手时校验 JWT + 比对 current_jti（被顶号后旧会话持有的
     * token jti 不再匹配 → 401，拒绝建立连接）。通过后把 SseEmitter 交给推送网关注册，
     * 后续业务事件（如 FORCE_LOGOUT）由网关即时推到前端。
     * 与 profile 等同属 auth 自服务，只验 JWT、不需要 @RequirePerm。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        String username;
        String jti;
        try {
            username = jwtUtil.getUsername(token);
            jti = jwtUtil.getJti(token);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        // jti 不匹配 = 该 token 已被同账号更新登录顶替（与 filter 的判定一致）。
        // 文案与 FORCE_LOGOUT 推送保持同一套语义，前端两条路径展示一致。
        if (user.getCurrentJti() == null || !jti.equals(user.getCurrentJti())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "您的账号已在其他设备登录，原会话已失效，请重新登录");
        }
        return realtimeGateway.connect(username, jti);
    }

    private String extractToken(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            return h.substring(7);
        }
        return null;
    }
}
