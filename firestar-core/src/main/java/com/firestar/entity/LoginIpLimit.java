package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 登录 IP 限流计数（爆破防护 L2，固定窗口）。
 *
 * <p>每个 IP 一行，记录当前窗口内的登录请求数。窗口过期后下一次请求把计数重置为 1 并刷新
 * window_start。用于防御「一密打千号」的密码喷射。同样用 MySQL 承载（firestar 无 Redis）。
 * 注意：X-Forwarded-For 可被伪造，仅作近似来源识别，不作为鉴权依据；高安全场景应叠加
 * 边缘 WAF（见调研文档 L6）。
 */
@Getter
@Setter
@TableName("sys_login_ip_limit")
public class LoginIpLimit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId = 0L;

    private String ipAddress;

    /** 当前窗口内请求数 */
    private Integer reqCount = 0;

    /** 当前计数窗口起点 */
    private LocalDateTime windowStart;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
