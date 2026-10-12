package com.firestar.dto;

import lombok.Data;

import java.util.List;

/**
 * 登录响应体。仅返回 token 与基本信息，权限/菜单由前端另行调 /auth/profile、/auth/menus 获取。
 */
@Data
public class LoginResponse {
    /** JWT Token（Bearer 后携带） */
    private String token;
    /** 有效期（毫秒） */
    private long expiresIn;
    private String username;
    private String nickname;
    /** 是否强制改密（首次登录后前端应跳转改密页） */
    private boolean forceChangePassword;
    /** 角色编码列表（如 ROLE_ADMIN） */
    private List<String> roles;
}
