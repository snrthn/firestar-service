package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.entity.HelpArticle;
import com.firestar.mapper.HelpArticleMapper;
import com.firestar.service.HelpArticleService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 帮助中心服务实现。前台只出 ENABLED；管理端 CRUD 全量。
 */
@Service
public class HelpArticleServiceImpl implements HelpArticleService {

    private final HelpArticleMapper mapper;

    public HelpArticleServiceImpl(HelpArticleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<HelpArticle> listEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<HelpArticle>()
                .eq(HelpArticle::getStatus, "ENABLED")
                .orderByAsc(HelpArticle::getSortNo)
                .orderByAsc(HelpArticle::getId));
    }

    @Override
    public IPage<HelpArticle> page(String keyword, String category, long pageNum, long pageSize) {
        return mapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<HelpArticle>()
                        .like(keyword != null && !keyword.isBlank(), HelpArticle::getTitle, keyword)
                        .eq(category != null && !category.isBlank(), HelpArticle::getCategory, category)
                        // 维护页按时间倒序（虎哥 2026-10-21）：最近编辑/新建的排最前，
                        // 改完立刻能在首行看到；id 兜底保证同一秒内的顺序稳定
                        .orderByDesc(HelpArticle::getUpdatedAt)
                        .orderByDesc(HelpArticle::getId));
    }

    @Override
    public HelpArticle detail(Long id) {
        HelpArticle a = mapper.selectById(id);
        if (a == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文章不存在");
        }
        return a;
    }

    @Override
    public Long create(Long operatorId, String title, String content, String category, Integer sortNo) {
        HelpArticle a = new HelpArticle();
        apply(a, title, content, category, sortNo, null);
        a.setCreatedBy(operatorId);
        mapper.insert(a);
        return a.getId();
    }

    @Override
    public void update(Long operatorId, Long id, String title, String content, String category,
                       Integer sortNo, String status) {
        HelpArticle a = detail(id);
        apply(a, title, content, category, sortNo, status);
        a.setUpdatedBy(operatorId);
        mapper.updateById(a);
    }

    @Override
    public void toggleStatus(Long operatorId, Long id) {
        HelpArticle a = detail(id);
        // 翻转：非 ENABLED 一律回 ENABLED（脏数据兜底，避免未知状态卡死）
        String next = "ENABLED".equals(a.getStatus()) ? "DISABLED" : "ENABLED";
        apply(a, null, null, null, null, next);
        a.setUpdatedBy(operatorId);
        mapper.updateById(a);
    }

    @Override
    public void delete(Long id) {
        detail(id);
        mapper.deleteById(id);
    }

    private void apply(HelpArticle a, String title, String content, String category,
                       Integer sortNo, String status) {
        a.setTitle(title);
        a.setContent(content);
        a.setCategory(category == null || category.isBlank() ? "通用" : category);
        a.setSortNo(sortNo == null ? 0 : sortNo);
        if (status != null) {
            if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法状态：" + status);
            }
            a.setStatus(status);
        }
    }
}
