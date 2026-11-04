-- ============================================================================
-- 【仅集成测试 classpath】补偿 V28 在 firestar_test 库重放时被跳过的 DDL
--
-- 根因：V28__avatar_column_and_about_us_rename.sql 的两处守卫写死
--       `table_schema = 'firestar'`，重放时查到 dev 库（列已加/已扩容）→ 跳过：
--         ① sys_user.avatar_url 未添加
--         ② sys_config.config_value 未扩容为 MEDIUMTEXT
--       这会让测试库 schema 与实体定义不一致（User.avatarUrl 查不到列）。
-- 处理：V27.1 在 V28 之前按当前库真实状态补齐，已应用的 V28 一个字符都不改。
-- ============================================================================
SET @db = DATABASE();

-- ① sys_user.avatar_url
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `sys_user` ADD COLUMN `avatar_url` VARCHAR(255) NULL COMMENT ''头像文件ID'' AFTER `email`',
    'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'sys_user' AND column_name = 'avatar_url');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② sys_config.config_value -> MEDIUMTEXT
SET @sql = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE `sys_config` MODIFY COLUMN `config_value` MEDIUMTEXT NULL COMMENT ''参数值（富文本场景）''',
    'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'sys_config'
      AND column_name = 'config_value' AND column_type <> 'mediumtext');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
