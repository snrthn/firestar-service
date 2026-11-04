package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.TokenBlacklist;
import org.apache.ibatis.annotations.Mapper;

/**
 * Token 黑名单 Mapper。仅 CRUD，无手写 SQL（守 V1 纪律）。
 */
@Mapper
public interface TokenBlacklistMapper extends BaseMapper<TokenBlacklist> {
}
