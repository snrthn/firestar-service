package com.firestar.controller;

import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.MenuRequest;
import com.firestar.dto.MenuVO;
import com.firestar.service.MenuService;
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
 * 菜单管理接口（前端 /api/system/menu/**，代理剥离 /api 后到 /system/menu/**）。
 *
 * 【路径约定】后端路由不带 /api（同 AuthController）；除 /auth/login 外默认需 JWT，
 * SecurityConfig 白名单未放行本路径，天然受保护。
 *
 * 【与 AuthController /auth/menus 的区别】那个按当前用户角色过滤、只返回可见菜单
 * 供侧栏渲染；本接口返回全量菜单树（含隐藏 / 停用）供菜单管理页维护。
 */
@RestController
@RequirePerm("rbac:menu")
@RequestMapping("/system/menu")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/list")
    public Result<List<MenuVO>> list() {
        return Result.success(menuService.listTree());
    }

    @PostMapping
    @RequirePerm("menu:create")
    public Result<Long> create(@RequestBody MenuRequest req) {
        return Result.success(menuService.create(req));
    }

    @PutMapping("/{id}")
    @RequirePerm({"menu:edit", "menu:create"})
    public Result<Void> update(@PathVariable Long id, @RequestBody MenuRequest req) {
        menuService.update(id, req);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @RequirePerm("menu:delete")
    public Result<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/move")
    public Result<Void> move(@PathVariable Long id, @RequestParam String direction) {
        menuService.move(id, direction);
        return Result.success();
    }
}
