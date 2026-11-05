package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.entity.LoginFail;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 登录失败计数表 Mapper（爆破防护 L1）。
 *
 * <p>{@link #incrementAndCreate} 用唯一索引 (tenant_id, username) 做原子「不存在则插入、
 * 存在则 +1」，避免并发自增竞态（RuoYi 审计点名的 race condition）。
 */
@Mapper
public interface LoginFailMapper extends BaseMapper<LoginFail> {

    @Insert("INSERT INTO sys_login_fail (tenant_id, username, fail_count, first_fail_at, created_at, updated_at) "
            + "VALUES (#{tenantId}, #{username}, 1, NOW(), NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE fail_count = fail_count + 1, updated_at = NOW()")
    void incrementAndCreate(@Param("tenantId") long tenantId, @Param("username") String username);

    @Update("UPDATE sys_login_fail "
            + "SET locked_until = DATE_ADD(NOW(), INTERVAL #{lockSeconds} SECOND), updated_at = NOW() "
            + "WHERE tenant_id = #{tenantId} AND username = #{username} "
            + "AND fail_count >= #{maxRetry} AND locked_until IS NULL")
    void lockIfThreshold(@Param("tenantId") long tenantId, @Param("username") String username,
                         @Param("lockSeconds") int lockSeconds, @Param("maxRetry") int maxRetry);

    default LoginFail byUsername(long tenantId, String username) {
        return selectOne(new LambdaQueryWrapper<LoginFail>()
                .eq(LoginFail::getTenantId, tenantId)
                .eq(LoginFail::getUsername, username));
    }
}
