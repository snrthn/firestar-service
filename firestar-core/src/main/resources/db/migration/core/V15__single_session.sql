-- ============================================================================
-- V15：单点登录（同一账号同时仅允许一个有效会话）
-- 思路：业界成熟的「服务端当前会话 JTI 注册表」模式（等价于 Spring Security
--       maximumSessions(1) 的并发会话控制），而非前端/localStorage 自管。
-- 机制：每个用户只保留一个 current_jti（当前有效会话标识）。登录时生成新 JTI 并
--       覆盖写入 current_jti；JwtAuthenticationFilter 每次请求校验 token 的 jti
--       是否仍等于该用户 current_jti，不等则 401（旧会话即时失效）。
--       后登录胜出（后踢前）= Spring Security 默认行为，用户不会被锁在门外。
-- 幂等：用 information_schema 判存在再 ALTER，重复执行无副作用。
-- 依赖：V1（sys_user 已存在）
-- ============================================================================

SET @db = DATABASE();
SET @has_jti = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'sys_user' AND column_name = 'current_jti'
);
SET @sql = IF(@has_jti = 0,
    'ALTER TABLE `sys_user` ADD COLUMN `current_jti` VARCHAR(64) NULL DEFAULT NULL COMMENT ''当前有效会话 JTI（单点登录：登录即轮换，旧 token 因 jti 不匹配即时失效）'' AFTER `last_login_ip`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 按 JTI 反查用户（踢人/审计场景），单列索引成本低
SET @has_idx = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = @db AND table_name = 'sys_user' AND index_name = 'idx_sys_user_current_jti'
);
SET @sql_idx = IF(@has_idx = 0,
    'CREATE INDEX `idx_sys_user_current_jti` ON `sys_user` (`current_jti`)',
    'SELECT 1');
PREPARE stmt_idx FROM @sql_idx;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;
