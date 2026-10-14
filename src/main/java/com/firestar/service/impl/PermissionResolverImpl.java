package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.entity.Permission;
import com.firestar.entity.Role;
import com.firestar.entity.RolePermission;
import com.firestar.mapper.PermissionMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.RolePermissionMapper;
import com.firestar.service.PermissionResolver;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色 → 权限解析实现：role → sys_role_permission → sys_permission，实时查库（不缓存）。
 */
@Service
public class PermissionResolverImpl implements PermissionResolver {

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;

    public PermissionResolverImpl(RoleMapper roleMapper, RolePermissionMapper rolePermissionMapper,
                                  PermissionMapper permissionMapper) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    @Override
    public List<String> resolve(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        List<Role> roles = roleMapper.selectList(
                new LambdaQueryWrapper<Role>().in(Role::getRoleCode, roleCodes));
        List<Long> roleIds = roles.stream().map(Role::getId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<RolePermission> rps = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().in(RolePermission::getRoleId, roleIds));
        Set<Long> permIds = rps.stream().map(RolePermission::getPermissionId).collect(Collectors.toSet());
        if (permIds.isEmpty()) {
            return List.of();
        }
        List<Permission> perms = permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>().in(Permission::getId, permIds));
        return perms.stream().map(Permission::getPermCode).collect(Collectors.toList());
    }
}
