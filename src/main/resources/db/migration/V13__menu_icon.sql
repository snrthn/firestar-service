-- ============================================================================
-- V13：菜单图标字段
-- 背景：侧边栏此前无图标数据，只能用 Folder/Document 兜底（SidebarNode.vue 注释
--       已说明 MenuVO 暂无 icon 字段）。一线 admin 系统（若依 / vue-element-admin）
--       通用做法是菜单表存「图标组件名」字符串，侧栏 <component :is> 渲染，
--       空值兜底默认图标。本脚本补列并把现有种子菜单按业务回填，保证视觉协调统一。
-- 纪律：已执行的 V1~V12 一律不动，增量 V13；ALTER 用 information_schema 判存在，
--       回填仅 UPDATE icon 为 NULL 的行，重复执行幂等。
-- ============================================================================

-- 1. 加列（幂等：表已有 icon 列则走 SELECT 1 空操作）
SET @db = DATABASE();
SET @has_col = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'sys_menu' AND COLUMN_NAME = 'icon'
);
SET @sql_add = IF(
  @has_col = 0,
  'ALTER TABLE `sys_menu` ADD COLUMN `icon` VARCHAR(64) NULL DEFAULT NULL COMMENT ''菜单图标：Element Plus 图标组件名（PascalCase），空则用默认图标兜底'' AFTER `status`',
  'SELECT 1'
);
PREPARE stmt_add FROM @sql_add;
EXECUTE stmt_add;
DEALLOCATE PREPARE stmt_add;

-- 2. 回填叶子菜单图标（按 component 唯一匹配；仅 icon 为空时填，幂等）
UPDATE `sys_menu` SET `icon` = CASE `component`
  WHEN 'dashboard/index'   THEN 'Odometer'
  WHEN 'project/list'      THEN 'OfficeBuilding'
  WHEN 'shipment/list'     THEN 'Box'
  WHEN 'receivable/list'   THEN 'Money'
  WHEN 'receipt/list'      THEN 'Wallet'
  WHEN 'invoice/list'      THEN 'Ticket'
  WHEN 'writeoff/list'     THEN 'Stamp'
  WHEN 'return/list'       THEN 'Refresh'
  WHEN 'approval/list'     THEN 'Bell'
  WHEN 'dashboard/data'    THEN 'DataLine'
  WHEN 'system/org'        THEN 'Share'
  WHEN 'system/user'       THEN 'User'
  WHEN 'system/role'       THEN 'Avatar'
  WHEN 'system/permission' THEN 'Lock'
  WHEN 'system/menu'       THEN 'Menu'
  WHEN 'system/dict'       THEN 'Collection'
  WHEN 'system/dict-data'  THEN 'Memo'
  WHEN 'system/file'       THEN 'Files'
  WHEN 'system/post'       THEN 'Postcard'
  ELSE `icon`
END
WHERE `component` <> '' AND `icon` IS NULL;

-- 3. 回填目录（CATALOG，component 为空）图标（按 menu_name 匹配；仅 icon 为空时填，幂等）
UPDATE `sys_menu` SET `icon` = CASE `menu_name`
  WHEN '项目管理' THEN 'Folder'
  WHEN '财务中心' THEN 'Coin'
  WHEN '业务中心' THEN 'Goods'
  WHEN '运营中心' THEN 'Operation'
  WHEN '系统管理' THEN 'Setting'
  ELSE `icon`
END
WHERE (`component` IS NULL OR `component` = '') AND `icon` IS NULL;
