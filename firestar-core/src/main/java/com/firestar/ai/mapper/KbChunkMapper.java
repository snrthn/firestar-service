package com.firestar.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.ai.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;

/** 知识库切片。步骤 4.5 仅用于发布时失效旧版本切片；切片生成属步骤 5。 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {
}
