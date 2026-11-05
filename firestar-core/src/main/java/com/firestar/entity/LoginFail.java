package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 登录失败计数与账号锁定状态（爆破防护 L1）。
 *
 * <p>单租户期 tenant_id 恒为 0。每个账号一行，失败次数累加；达到阈值后置 locked_until，
 * 锁定期间登录被拒。登录成功即删除该行（清零）。全部由 MySQL 承载——firestar 当前未接入
 * Redis，因此没有用 Redis 计数器，而是用唯一索引 (tenant_id, username) + 原子
 * INSERT ... ON DUPLICATE KEY UPDATE 保证并发安全。
 */
@Getter
@Setter
@TableName("sys_login_fail")
public class LoginFail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId = 0L;

    private String username;

    /** 连续失败次数 */
    private Integer failCount = 0;

    /** 首次失败时间（窗口起点，用于审计） */
    private LocalDateTime firstFailAt;

    /** 锁定到期时刻；null 表示未锁定 */
    private LocalDateTime lockedUntil;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
