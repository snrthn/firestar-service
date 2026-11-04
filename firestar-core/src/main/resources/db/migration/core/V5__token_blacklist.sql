-- ============================================================================
-- V4：sys_token_blacklist Token 黑名单
-- 用途：logout 后将 JWT 的 jti 写入本表，使该 token 在有效期内提前失效。
-- 与 jjwt 签发时生成的 jti（唯一标识）对应。expire_at 用于后续清理过期行。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `sys_token_blacklist` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `token_id`   VARCHAR(64) NOT NULL COMMENT 'JWT 的 jti（唯一标识），登出时拉黑',
    `username`   VARCHAR(64) NULL COMMENT '用户名（便于排查）',
    `expire_at`  DATETIME    NOT NULL COMMENT '原 token 的过期时间，过期后该行可清理',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '拉黑时间',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_id` (`token_id`),
    KEY `idx_expire` (`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Token 黑名单';
