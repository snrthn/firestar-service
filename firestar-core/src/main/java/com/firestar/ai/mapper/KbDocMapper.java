package com.firestar.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.ai.entity.KbDoc;
import org.apache.ibatis.annotations.Mapper;

/** 知识库节点（文档 / 文件夹）。泛型注册进 GenericEntityLoader，供 @Auditable 取 before 快照。 */
@Mapper
public interface KbDocMapper extends BaseMapper<KbDoc> {
}
