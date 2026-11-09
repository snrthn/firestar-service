package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.HelpArticle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HelpArticleMapper extends BaseMapper<HelpArticle> {

    /** 取所有启用状态的帮助文章（供知识库检索合并召回；步骤 5 沿用既有帮助中心表） */
    @Select("SELECT id, title, content, category, status " +
            "FROM sys_help_article WHERE status = 'ENABLED'")
    List<HelpArticle> selectEnabled();
}
