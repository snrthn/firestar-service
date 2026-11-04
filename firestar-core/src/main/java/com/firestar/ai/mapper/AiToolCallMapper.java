package com.firestar.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.ai.entity.AiToolCall;
import org.apache.ibatis.annotations.Mapper;

/** ai_tool_calls 的 Mapper（V100 新增）。 */
@Mapper
public interface AiToolCallMapper extends BaseMapper<AiToolCall> {
}
