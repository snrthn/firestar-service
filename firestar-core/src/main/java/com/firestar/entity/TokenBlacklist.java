package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Token 黑名单（logout 后使 JWT 提前失效）。
 * 不继承 AuditedEntity：本表是纯运维记录，无需 created_by / version 等审计字段。
 */
@Getter
@Setter
@TableName("sys_token_blacklist")
public class TokenBlacklist {

    private Long id;

    /** JWT 的 jti（唯一标识） */
    private String tokenId;

    /** 用户名（便于排查） */
    private String username;

    /** 原 token 的过期时间 */
    private LocalDateTime expireAt;

    private LocalDateTime createdAt;
}
