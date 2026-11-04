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

    /** 手机号（可空） */
    private String phone;
    /** 邮箱（可空） */
    private String email;
    /** 所属部门名称（服务层按 deptId 回填 sys_org.org_name） */
    private String deptName;
    /** 岗位名称（服务层按 postId 回填 sys_post.post_name） */
    private String postName;
    /** 最后登录时间 */
    private java.time.LocalDateTime lastLoginAt;
    /** 创建时间 */
    private java.time.LocalDateTime createdAt;
    /** 最后登录 IP */
    private String lastLoginIp;
    /** 直属上级名称（服务层按 parentId 回填 sys_user.nickname） */
    private String parentName;
    /** 头像文件ID（sys_file_storage.id）；为空则前端昵称首字兜底 */
    private String avatarUrl;
}
