package com.firestar.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.ai.entity.KbAttachment;
import org.apache.ibatis.annotations.Mapper;

/** 知识库附件关系（INLINE / REFERENCE / ORIGINAL / EXPIRED）。 */
@Mapper
public interface KbAttachmentMapper extends BaseMapper<KbAttachment> {
}
