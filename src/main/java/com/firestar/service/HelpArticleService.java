package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.entity.HelpArticle;

import java.util.List;

/**
 * 帮助中心服务。
 */
public interface HelpArticleService {

    /** 前台列表（仅 ENABLED，sort_no 升序；不要求权限点，登录即可） */
    List<HelpArticle> listEnabled();

    /** 管理端分页（含停用；keyword 按标题模糊、category 精确匹配，均可选） */
    IPage<HelpArticle> page(String keyword, String category, long pageNum, long pageSize);

    /** 详情 */
    HelpArticle detail(Long id);

    /** 新建（管理端） */
    Long create(Long operatorId, String title, String content, String category, Integer sortNo);

    /** 更新（管理端） */
    void update(Long operatorId, Long id, String title, String content, String category,
                Integer sortNo, String status);

    /** 删除（管理端，物理删除） */
    void delete(Long id);
}
