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
 *
 * <p>方案 B-③ 严格分离（2026-10-30 虎哥拍板）：本类只解析【操作权限】，供后端
 * {@code @RequirePerm} 接口鉴权使用；按钮展示走另一条线 —— {@code sys_menu} 的
 * BUTTON 节点 perm_code（见 {@code AuthServiceImpl.loadPermissions}）。两条线不再 union。
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
        // 方案 A（2026-11-04 停用语义统一）：三层关联全部要求 status=ENABLED。
        // 之前三处都没带 status，导致「停用角色 / 停用权限 / 停用角色-权限关联」
        // 对接口鉴权毫无影响（字段存在、写入时校验取值，读取时被忽略）。
        // 本类实时查库不缓存 → 停用后下个请求即生效，无需撤销 token。
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .in(Role::getRoleCode, roleCodes)
                .eq(Role::getStatus, "ENABLED"));
        List<Long> roleIds = roles.stream().map(Role::getId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        // 方案 B-③ 严格分离：接口放行【只认操作权限】sys_role_permission，
        // 不并入菜单 BUTTON 节点 perm_code —— 那是【菜单权限】的事，只管按钮展示
        // （见 AuthServiceImpl.loadPermissions）。
        // ⚠️ 由此产生的预期行为：勾了菜单按钮但没勾操作权限 → 按钮显示、点击 403。
        //    这是严格分离的代价，虎哥已知并接受（2026-10-30 拍板）。
        List<RolePermission> rps = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>()
                        .in(RolePermission::getRoleId, roleIds)
                        .eq(RolePermission::getStatus, "ENABLED"));
        Set<Long> permIds = rps.stream().map(RolePermission::getPermissionId).collect(Collectors.toSet());
        if (permIds.isEmpty()) {
            return List.of();
        }
        return permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                        .in(Permission::getId, permIds)
                        .eq(Permission::getStatus, "ENABLED"))
                .stream().map(Permission::getPermCode).collect(Collectors.toList());
    }
}
