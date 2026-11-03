package com.firestar.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.ai.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;

/** ai_messages 的 Mapper（V100 新增）。 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {
}
