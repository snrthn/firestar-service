-- ============================================================================
-- 【仅集成测试 classpath】补偿 V27 在 firestar_test 库重放时被跳过的 DDL
--
-- 根因：V27__add_ext_to_agreement_tables.sql 的 information_schema 守卫写死
--       `table_schema = 'firestar'`。在 firestar_test 里重放时，它查的是 dev 库
--       （dev 库 V27 早已执行、ext 已存在）→ NOT EXISTS 为 false → 跳过加列，
--       于是当前库的三张协议表缺 ext，MyBatis-Plus 拼 SELECT ... ext 即报
--       Unknown column 'ext'。
--
-- 为什么不改 V27：该迁移在 dev / pre / prod 的 flyway_schema_history 里已登记，
--       改一个字符就会触发 checksum 校验失败（项目 checksum 铁律）。
--       故用 V26.1 在 V27 之前插入本补偿；生产四个 profile 的 locations 不含本目录，零影响。
--
-- 幂等：用 DATABASE() 判定当前库，不写死库名。
-- ============================================================================
SET @db = DATABASE();

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `sys_agreement` ADD COLUMN `ext` JSON NULL COMMENT ''扩展字段''', 'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'sys_agreement' AND column_name = 'ext');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `sys_agreement_version` ADD COLUMN `ext` JSON NULL COMMENT ''扩展字段''', 'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'sys_agreement_version' AND column_name = 'ext');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `sys_agreement_consent` ADD COLUMN `ext` JSON NULL COMMENT ''扩展字段''', 'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'sys_agreement_consent' AND column_name = 'ext');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
