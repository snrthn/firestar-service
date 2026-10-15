-- ============================================================================
-- V16：菜单外链与页面缓存支持
-- 背景（虎哥 2026-10-15 需求）：
--   ① 菜单类型新增 BUTTON（按钮）/ LINK（外链），类型切换时表单按需显隐字段；
--   ② LINK 型跳转地址单独一列 link_url，由前后端双重校验（http(s)://，防乱填）；
--   ③ 页面缓存 is_cache（keep-alive）：页签来回切换时保留页面状态。
-- 种子口径：is_cache 一律默认 0（不缓存）——缓存是「选了才有」的增强项，
--   种子菜单显式不带该列走 DDL 默认；字典补 menu_type/LINK 项。
-- 幂等：DDL 用 information_schema 判存在再 ALTER（同 V11），字典 INSERT 走
--   ON DUPLICATE KEY（同 V2 风格），重复执行无副作用。
-- 依赖：V1（sys_menu / sys_dict_data 已建）
-- ============================================================================

-- ---- 1. link_url：外链地址（仅 menu_type=LINK 使用，服务端强校验）----
SET @db = DATABASE();
SET @has_link_url = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'sys_menu' AND column_name = 'link_url'
);
SET @sql = IF(@has_link_url = 0,
    'ALTER TABLE `sys_menu` ADD COLUMN `link_url` VARCHAR(500) NULL COMMENT ''外链跳转地址（menu_type=LINK 时必填，须以 http(s):// 开头）'' AFTER `route_path`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---- 2. is_cache：页面缓存开关（0 不缓存 1 缓存；仅 MENU 有意义）----
SET @has_is_cache = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'sys_menu' AND column_name = 'is_cache'
);
SET @sql = IF(@has_is_cache = 0,
    'ALTER TABLE `sys_menu` ADD COLUMN `is_cache` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否缓存页面（keep-alive）：0 不缓存 1 缓存；仅 MENU 型有意义，种子默认 0'' AFTER `visible`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---- 3. 字典 menu_type 补「外链」项（列名与 V2 种子对齐：type_code/sort_no）----
-- 注：既有字典项 '目录' 的值为 DIRECTORY，与代码域值 CATALOG 有历史偏差
-- （字典页展示用，非本次范围，不动）。此处只补新增的 LINK。
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES ('menu_type', '外链', 'LINK', 4, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `is_default` = VALUES(`is_default`);
