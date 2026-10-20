package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.entity.HelpArticle;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.service.HelpArticleService;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帮助中心维护接口（系统管理模块，隐藏菜单 /system/help-manage）。
 */
@RestController
@RequestMapping("/system/help")
@RequirePerm("system:help")
public class HelpManageController {

    private final HelpArticleService helpArticleService;
    private final UserMapper userMapper;

    public HelpManageController(HelpArticleService helpArticleService, UserMapper userMapper) {
        this.helpArticleService = helpArticleService;
        this.userMapper = userMapper;
    }

    /** 分页（含停用，keyword 按标题模糊、category 按分类精确筛选） */
    @GetMapping("/page")
    public Result<IPage<HelpArticle>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String category) {
        return Result.success(helpArticleService.page(keyword, category, pageNum, pageSize));
    }

    /** 详情 */
    @GetMapping("/{id}")
    public Result<HelpArticle> detail(@PathVariable Long id) {
        return Result.success(helpArticleService.detail(id));
    }

    /** 新建 */
    @PostMapping
    public Result<Long> create(@RequestBody HelpRequest req) {
        return Result.success(helpArticleService.create(
                currentUserId(), req.getTitle(), req.getContent(),
                req.getCategory(), req.getSortNo()));
    }

    /** 更新 */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody HelpRequest req) {
        helpArticleService.update(currentUserId(), id, req.getTitle(), req.getContent(),
                req.getCategory(), req.getSortNo(), req.getStatus());
        return Result.success();
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        helpArticleService.delete(id);
        return Result.success();
    }

    private Long currentUserId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).select(User::getId));
        if (u == null) {
            throw new IllegalStateException("当前登录用户不存在");
        }
        return u.getId();
    }

    @Getter
    @Setter
    public static class HelpRequest {
        private String title;
        private String content;
        private String category;
        private Integer sortNo;
        private String status;
    }
}
