package com.firestar.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.ai.entity.AiConversation;
import org.apache.ibatis.annotations.Mapper;

/** ai_conversations 的 Mapper（V100 新增）。 */
@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {
}
