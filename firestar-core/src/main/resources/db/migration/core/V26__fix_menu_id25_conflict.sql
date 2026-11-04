-- ============================================================================
-- V26：修复 V25 菜单 id 冲突（参数设置误占文件管理目录 id=25）
-- 背景：V19 已将「文件管理」升为一级目录 id=25（下挂 文件列表 18 / 下载记录 27）。
--       V25 种子未核对全量菜单 id 占用，INSERT ... ON DUPLICATE KEY UPDATE 把
--       id=25 原地改写为「参数设置」MENU → 文件管理目录消失，
--       文件列表/下载记录被错误挂到「参数设置」下（侧边栏展示错乱）。
-- 修复：
--   ① 恢复 id=25 = 文件管理一级目录（V19 原始定义；icon 保持 FolderOpened 不动）
--   ② 参数设置菜单改用空闲 id=31，挂系统管理(12)下 sort_no=8（7 已被字典管理占用）
--   ③ ROLE_ADMIN 补授权菜单 31（25 的既有授权保留 = 文件管理本来就该有）
-- 幂等：固定值 UPDATE + INSERT ... ON DUPLICATE KEY UPDATE，重复执行结果一致。
-- ============================================================================

-- ① 恢复 id=25 文件管理一级目录（V19 第 146 行原始定义）
UPDATE `sys_menu` SET
    `menu_name`  = '文件管理',
    `parent_id`  = 0,
    `route_path` = '',
    `component`  = '',
    `perm_code`  = NULL,
    `menu_type`  = 'CATALOG',
    `sort_no`    = 6,
    `visible`    = 1,
    `status`     = 'ENABLED'
WHERE `id` = 25;

-- ② 参数设置菜单（新 id=31，系统管理 id=12 下，sort_no=8）
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`) VALUES
(31, '参数设置', 12, '/system/param-setting', 'system/param-setting', 'config:manage', 'MENU', 8, 1, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`);

-- ③ ROLE_ADMIN 授权参数设置菜单
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, 31, 0
FROM `sys_role` r WHERE r.role_code = 'ROLE_ADMIN'
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
