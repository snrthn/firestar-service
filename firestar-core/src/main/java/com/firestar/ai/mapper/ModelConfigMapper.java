package com.firestar.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.ai.entity.ModelConfig;
import org.apache.ibatis.annotations.Mapper;

/** sys_model_config 的 Mapper（V99 新增）。 */
@Mapper
public interface ModelConfigMapper extends BaseMapper<ModelConfig> {
}
