package com.firestar.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.ai.entity.AiPromptConfig;

/**
 * AI 提示词方案 Mapper（sys_ai_prompt，V105 新增）。
 * 与同包 ModelConfigMapper 一致，由 @MapperScan 统一扫描注册。
 */
public interface AiPromptConfigMapper extends BaseMapper<AiPromptConfig> {
}
