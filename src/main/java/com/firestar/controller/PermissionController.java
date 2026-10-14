package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.PermissionRequest;
import com.firestar.dto.PermissionVO;
import com.firestar.service.PermissionService;
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
 * 权限管理接口（前端 /api/system/permission/**，代理剥离 /api 后到 /system/permission/**）。
 *
 * 【路径约定】后端路由不带 /api（同 MenuController）；除登录外默认需 JWT，
 * SecurityConfig 白名单未放行本路径，天然受保护。
 * 菜单 15「权限管理」挂 perm_code=rbac:perm。
 */
@RestController
@RequirePerm("rbac:perm")
@RequestMapping("/system/permission")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** 全量权限树（管理页展示用） */
    @GetMapping("/list")
    public Result<List<PermissionVO>> list() {
        return Result.success(permissionService.listTree());
    }

    /** 分页权限列表（permName/permCode 模糊、status 精确） */
    @GetMapping("/page")
    public Result<IPage<PermissionVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String permName,
            @RequestParam(required = false) String permCode,
            @RequestParam(required = false) String status) {
        return Result.success(permissionService.pagePerms(pageNum, pageSize, permName, permCode, status));
    }

    @PostMapping
    public Result<Long> create(@RequestBody PermissionRequest req) {
        return Result.success(permissionService.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody PermissionRequest req) {
        permissionService.update(id, req);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionService.delete(id);
        return Result.success();
    }
}
