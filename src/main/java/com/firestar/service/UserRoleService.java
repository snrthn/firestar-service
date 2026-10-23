package com.firestar.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.entity.Role;
import com.firestar.entity.UserRole;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserRoleMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户-角色成员关系查询：按用户实时查其拥有的角色编码（role_code）列表。
 *
 * 与 AuthServiceImpl.loadRoles 同源（都走 sys_user_role + sys_role），但本类只依赖 mapper、
 * 不依赖任何业务上下文，专供 JwtAuthenticationFilter 在每次请求时重建 authorities，
 * 使「变更用户角色」即时生效（无需重登）。
 */
@Service
public class UserRoleService {

    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;

    public UserRoleService(UserRoleMapper userRoleMapper, RoleMapper roleMapper) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
    }

    /**
     * 按用户 ID 查角色编码列表。
     *
     * @return 角色编码（如 ROLE_ADMIN）列表；用户无角色或 userId 为空时返回空列表（不会返回 null）
     */
    public List<String> findRoleCodesByUserId(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId))
                .stream()
                .map(UserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectList(new LambdaQueryWrapper<Role>().in(Role::getId, roleIds))
                .stream()
                .map(Role::getRoleCode)
                .toList();
    }
}
