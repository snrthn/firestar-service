package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.UserOptionVO;
import com.firestar.dto.UserRequest;
import com.firestar.dto.UserVO;
import com.firestar.service.UserService;
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
 * 用户管理接口（前端 /api/system/user/**，代理剥离 /api 后到 /system/user/**）。
 *
 * 【路径约定】后端路由不带 /api（同 MenuController）；除登录外默认需 JWT，
 * SecurityConfig 白名单未放行本路径，天然受保护。
 *
 * 【分工】/options 是给组织架构等模块用的只读下拉（全量、轻量）；
 * 其余为管理端 CRUD（分页），菜单 13「用户管理」挂 perm_code=rbac:user。
 */
@RestController
@RequirePerm("rbac:user")
@RequestMapping("/system/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 启用用户下拉选项（供组织架构负责人选择等场景） */
    @GetMapping("/options")
    public Result<List<UserOptionVO>> options() {
        return Result.success(userService.listOptions());
    }

    /** 分页用户列表（username/nickname 模糊，status/deptId 精确；默认 1/10，size 上限 500） */
    @GetMapping("/page")
    public Result<IPage<UserVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String nickname,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long deptId) {
        return Result.success(userService.pageUsers(pageNum, pageSize, username, nickname, status, deptId));
    }

    @PostMapping
    @RequirePerm("user:create")
    public Result<Long> create(@RequestBody UserRequest req) {
        return Result.success(userService.createUser(req));
    }

    @PutMapping("/{id}")
    @RequirePerm({"user:edit", "user:create"})
    public Result<Void> update(@PathVariable Long id, @RequestBody UserRequest req) {
        userService.updateUser(id, req);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @RequirePerm("user:delete")
    public Result<Void> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return Result.success();
    }
}
