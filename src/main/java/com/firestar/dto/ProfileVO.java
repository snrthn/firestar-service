package com.firestar.dto;

import lombok.Data;

import java.util.List;

/**
 * 当前用户信息（/auth/profile）。含账号信息、角色、权限编码列表。
 */
@Data
public class ProfileVO {
    private String username;
    private String nickname;
    /** 状态（字典 common_status：ENABLED / DISABLED 等） */
    private String status;
    /** 是否强制改密 */
    private boolean forceChangePassword;
    private List<RoleVO> roles;
    /** 权限编码列表（来自 sys_permission.perm_code） */
    private List<String> permissions;
}
