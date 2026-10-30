package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.PostVO;
import com.firestar.dto.PostRequest;
import com.firestar.service.PostService;
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
 * 岗位管理接口（前端 /api/system/post/**，代理剥离 /api 后到 /system/post/**）。
 *
 * 【路径约定】后端路由不带 /api（同 RoleController）；除登录外默认需 JWT。
 * 菜单 24「岗位管理」挂 perm_code=rbac:post。
 */
@RestController
@RequirePerm("rbac:post")
@RequestMapping("/system/post")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    /** 分页岗位列表（postName 模糊、status 精确；默认 1/10，size 上限 500） */
    @GetMapping("/page")
    public Result<IPage<PostVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String postName,
            @RequestParam(required = false) String status) {
        return Result.success(postService.pagePosts(pageNum, pageSize, postName, status));
    }

    @PostMapping
    @RequirePerm("post:create")
    public Result<Long> create(@RequestBody PostRequest req) {
        return Result.success(postService.createPost(req));
    }

    @PutMapping("/{id}")
    @RequirePerm("post:edit")
    public Result<Void> update(@PathVariable Long id, @RequestBody PostRequest req) {
        postService.updatePost(id, req);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @RequirePerm("post:delete")
    public Result<Void> delete(@PathVariable Long id) {
        postService.deletePost(id);
        return Result.success();
    }

    /** 启用岗位下拉（创建用户时选择，非必填） */
    @GetMapping("/options")
    public Result<List<PostVO>> options() {
        return Result.success(postService.listOptions());
    }
}
