package com.firestar.service;

import com.firestar.dto.ChangePasswordRequest;
import com.firestar.dto.LoginRequest;
import com.firestar.dto.LoginResponse;
import com.firestar.dto.MenuVO;
import com.firestar.dto.ProfileUpdateRequest;
import com.firestar.dto.ProfileVO;

import java.util.List;

/**
 * 认证服务：登录、登出（黑名单）、当前用户信息、改密、动态菜单树。
 */
public interface AuthService {

    /**
     * 登录。clientIp 为客户端真实 IP（由 Controller 从 HttpServletRequest 提取，
     * 可为 null），登录成功时一并写入 sys_user.last_login_ip 作审计留痕。
     */
    LoginResponse login(LoginRequest req, String clientIp, String userAgent);

    void logout(String token);

    ProfileVO profile();

    void changePassword(ChangePasswordRequest req);

    /** 更新当前登录用户本人可自助维护的资料（昵称 / 手机号 / 邮箱） */
    void updateProfile(ProfileUpdateRequest req);

    List<MenuVO> menus();
}
