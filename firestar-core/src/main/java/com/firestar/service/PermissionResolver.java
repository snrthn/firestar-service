package com.firestar.service;

import java.util.List;

/**
 * 角色编码 → 权限编码解析。与 AuthServiceImpl 的权限计算同源，但独立成 bean，
 * 供 JwtAuthenticationFilter 在每次请求时实时解析用户权限（保证权限变更即时生效，
 * 不缓存，与 RBAC 设计哲学一致）。
 */
public interface PermissionResolver {
    /**
     * @param roleCodes 当前用户角色编码（如 [ROLE_ADMIN, rbac_editor]）
     * @return 该用户拥有的全部 perm_code（sys_permission.perm_code），去重
     */
    List<String> resolve(List<String> roleCodes);
}
