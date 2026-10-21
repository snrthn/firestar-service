package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.LevelVO;
import com.firestar.dto.LevelRequest;
import com.firestar.service.LevelService;
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
 * 职级管理接口（前端 /api/system/level/**，代理剥离 /api 后到 /system/level/**）。
 *
 * 【路径约定】后端路由不带 /api（同 PostController/RoleController）；除登录外默认需 JWT。
 * 菜单 93「职级管理」挂 perm_code=level:view。
 */
@RestController
@RequirePerm("level:view")
@RequestMapping("/system/level")
public class LevelController {

    private final LevelService levelService;

    public LevelController(LevelService levelService) {
        this.levelService = levelService;
    }

    /** 分页职级列表（levelName/levelCode 模糊、status 精确；默认 1/10，size 上限 500） */
    @GetMapping("/page")
    public Result<IPage<LevelVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String levelName,
            @RequestParam(required = false) String levelCode,
            @RequestParam(required = false) String status) {
        return Result.success(levelService.pageLevels(pageNum, pageSize, levelName, levelCode, status));
    }

    @PostMapping
    public Result<Long> create(@RequestBody LevelRequest req) {
        return Result.success(levelService.createLevel(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody LevelRequest req) {
        levelService.updateLevel(id, req);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        levelService.deleteLevel(id);
        return Result.success();
    }

    /** 启用职级下拉（用户表单职级选择、流程节点 LEVEL 配置等场景） */
    @GetMapping("/options")
    public Result<List<LevelVO>> options() {
        return Result.success(levelService.listOptions());
    }
}
