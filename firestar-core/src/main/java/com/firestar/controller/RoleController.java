package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.RoleManageVO;
import com.firestar.dto.RoleMenuRequest;
import com.firestar.dto.RolePermRequest;
import com.firestar.dto.RoleRequest;
import com.firestar.service.RoleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理接口（前端 /api/system/role/**，代理剥离 /api 后到 /system/role/**）。
 *
 * 【路径约定】后端路由不带 /api（同 MenuController）；除登录外默认需 JWT，
 * SecurityConfig 白名单未放行本路径，天然受保护。
 * 菜单 14「角色管理」挂 perm_code=rbac:role；权限分配弹窗走 /{id}/perms。
 */
@RestController
@RequirePerm("rbac:role")
@RequestMapping("/system/role")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    /** 分页角色列表（roleName 模糊、status 精确；默认 1/10，size 上限 500） */
    @GetMapping("/page")
    public Result<IPage<RoleManageVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) String status) {
        return Result.success(roleService.pageRoles(pageNum, pageSize, roleName, status));
    }

    @PostMapping
    @RequirePerm("role:create")
    public Result<Long> create(@RequestBody RoleRequest req) {
        return Result.success(roleService.createRole(req));
    }

    @PutMapping("/{id}")
    @RequirePerm("role:edit")
    public Result<Void> update(@PathVariable Long id, @RequestBody RoleRequest req) {
        roleService.updateRole(id, req);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @RequirePerm("role:delete")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.deleteRole(id);
        return Result.success();
    }

    /** 角色已授权的权限 ID 集合（分配弹窗回显勾选） */
    @GetMapping("/{id}/perms")
    public Result<List<Long>> permIds(@PathVariable Long id) {
        return Result.success(roleService.listPermIds(id));
    }

    /** 分配权限（整体替换语义，空集合 = 清空） */
    @PutMapping("/{id}/perms")
    @RequirePerm("role:assign")
    public Result<Void> assignPerms(@PathVariable Long id, @RequestBody RolePermRequest req) {
        roleService.assignPerms(id, req);
        return Result.success();
    }

    /** 角色已授权的菜单 ID 集合（分配弹窗回显勾选） */
    @GetMapping("/{id}/menus")
    public Result<List<Long>> menuIds(@PathVariable Long id) {
        return Result.success(roleService.listMenuIds(id));
    }

    /** 分配菜单（整体替换语义，空集合 = 清空） */
    @PutMapping("/{id}/menus")
    @RequirePerm("role:assign")
    public Result<Void> assignMenus(@PathVariable Long id, @RequestBody RoleMenuRequest req) {
        roleService.assignMenus(id, req);
        return Result.success();
    }
}
