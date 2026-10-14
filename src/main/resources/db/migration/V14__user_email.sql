-- ============================================================================
-- V14：补齐 sys_user.email 邮箱列
-- 背景：用户此前只有 phone（V10 加的手机号），缺邮箱字段；本次前后端统一补上
--       （实体 User / UserRequest / UserVO + 前端 types/user.ts 与 user.vue）。
--       不加唯一约束——邮箱非登录标识、允许为空、允许多人共用（与 RuoYi 口径一致），
--       避免历史空值数据与唯一索引冲突。
-- 幂等：用 information_schema 判存在再 ALTER，重复执行无副作用。
-- 依赖：V10（sys_user.phone 已存在，email 紧随其后）
-- ============================================================================

SET @db = DATABASE();
SET @has_email = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'sys_user' AND column_name = 'email'
);
SET @sql = IF(@has_email = 0,
    'ALTER TABLE `sys_user` ADD COLUMN `email` VARCHAR(128) NULL COMMENT ''邮箱'' AFTER `phone`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
