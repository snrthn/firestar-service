-- ============================================================================
-- V11：补齐 sys_post 缺失的 ext 扩展列
-- 背景：V10 建 sys_post 时漏了 BaseEntity 四件套里的 ext（JSON）列，
--       而 Post 实体继承 BaseEntity 会把 ext 映射到列 `ext`。
--       结果所有对 sys_post 的查询（岗位分页 / 更新前 selectById /
--       用户列表回填岗位名时 JOIN sys_post）都报
--       `Unknown column 'ext' in 'field list'`，岗位创建却正常
--       （insert 不引用 ext）——典型的 DDL 与实体不一致。
-- 修复：给 sys_post 补 ext JSON 列，与其它 24 张表对齐（见 V1 基线）。
-- 幂等：用 information_schema 判存在再 ALTER，重复执行无副作用。
-- 依赖：V10（sys_post 已建）
-- ============================================================================

SET @db = DATABASE();
SET @has_ext = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'sys_post' AND column_name = 'ext'
);
SET @sql = IF(@has_ext = 0,
    'ALTER TABLE `sys_post` ADD COLUMN `ext` JSON NULL COMMENT ''扩展字段（禁放金额/状态/检索字段）'' AFTER `version`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
