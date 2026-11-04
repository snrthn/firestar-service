package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.LoginLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录日志 Mapper。V1 纪律：一律继承 BaseMapper，零手写 SQL。
 */
@Mapper
public interface LoginLogMapper extends BaseMapper<LoginLog> {
}
