package com.firestar.controller;

import com.firestar.common.Result;
import com.firestar.entity.HelpArticle;
import com.firestar.service.HelpArticleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 帮助中心前台接口（登录即可访问，不挂权限点——全员自助内容）。
 * 维护端见 HelpManageController（/system/help，@RequirePerm("system:help")）。
 */
@RestController
@RequestMapping("/help")
public class HelpArticleController {

    private final HelpArticleService helpArticleService;

    public HelpArticleController(HelpArticleService helpArticleService) {
        this.helpArticleService = helpArticleService;
    }

    /** 文章列表（仅启用，sort_no 升序） */
    @GetMapping("/articles")
    public Result<List<HelpArticle>> list() {
        return Result.success(helpArticleService.listEnabled());
    }

    /** 文章详情 */
    @GetMapping("/articles/{id}")
    public Result<HelpArticle> detail(@PathVariable Long id) {
        return Result.success(helpArticleService.detail(id));
    }
}
