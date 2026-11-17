package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.LoginIpLimit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 登录 IP 限流计数表 Mapper（爆破防护 L2）。
 *
 * <p>{@link #incrementAndCreate} 用唯一索引 (tenant_id, ip_address) 做原子「窗口过期则重置为 1、
 * 否则 +1」，实现固定窗口限流，无需 Redis。
 */
@Mapper
public interface LoginIpLimitMapper extends BaseMapper<LoginIpLimit> {

    @Insert("INSERT INTO sys_login_ip_limit (tenant_id, ip_address, req_count, window_start, created_at, updated_at) "
            + "VALUES (#{tenantId}, #{ip}, 1, NOW(), NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE "
            + "req_count = IF(window_start < DATE_SUB(NOW(), INTERVAL #{window} SECOND), 1, req_count + 1), "
            + "window_start = IF(window_start < DATE_SUB(NOW(), INTERVAL #{window} SECOND), NOW(), window_start), "
            + "updated_at = NOW()")
    void incrementAndCreate(@Param("tenantId") long tenantId, @Param("ip") String ip,
                           @Param("window") int window);

    @Select("SELECT req_count FROM sys_login_ip_limit WHERE tenant_id = #{tenantId} AND ip_address = #{ip}")
    Integer selectReqCount(@Param("tenantId") long tenantId, @Param("ip") String ip);
}
