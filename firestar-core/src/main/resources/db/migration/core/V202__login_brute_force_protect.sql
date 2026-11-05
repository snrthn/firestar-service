-- ===========================================================================
-- 登录爆破防护（L1 账户锁定 + L2 IP 限流 + 审计闭环）
-- 2026-11-05：社区(OWASP/NIST/RuoYi/Keycloak) + 大厂(微软/谷歌/GitHub/Cloudflare/AWS)调研后落地。
--
-- ⚠️ firestar 当前未接入 Redis，全部状态落 MySQL（与全栈 MyBatis-Plus 一致）。
--    两张状态表用唯一索引 + 原子 INSERT ... ON DUPLICATE KEY UPDATE 保证并发安全，
--    不依赖 Redis / 分布式锁。
--
-- 计数影响：新增 2 张业务表（sys_login_fail / sys_login_ip_limit）→ EXPECTED_TABLE_COUNT 46→48；
--           新增 2 行字典数据（login_status 的 LOCKED / UNLOCKED）→ EXPECTED_DICT_DATA_COUNT 148→150。
-- ===========================================================================

-- 1) L1：登录失败计数 + 账户锁定
CREATE TABLE IF NOT EXISTS `sys_login_fail` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0,
    `username`      VARCHAR(64)  NOT NULL,
    `fail_count`    INT          NOT NULL DEFAULT 0 COMMENT '连续失败次数',
    `first_fail_at` DATETIME     DEFAULT NULL COMMENT '首次失败时间（窗口起点）',
    `locked_until`  DATETIME     DEFAULT NULL COMMENT '非 null 表示锁定到此时刻',
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_username` (`tenant_id`, `username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='登录失败计数与账号锁定（爆破防护 L1）';

-- 2) L2：登录 IP 限流（固定窗口）
CREATE TABLE IF NOT EXISTS `sys_login_ip_limit` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0,
    `ip_address`    VARCHAR(64)  NOT NULL,
    `req_count`     INT          NOT NULL DEFAULT 0 COMMENT '当前窗口内请求数',
    `window_start`  DATETIME     DEFAULT NULL COMMENT '当前计数窗口起点',
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_ip` (`tenant_id`, `ip_address`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='登录 IP 限流计数（爆破防护 L2，防御密码喷射）';

-- 3) login_status 字典补 LOCKED / UNLOCKED（爆破防护动作审计用）
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('login_status', '锁定',   'LOCKED',   4, 0, '启用', 0),
('login_status', '解锁',   'UNLOCKED', 5, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);
