-- ============================================================================
-- V36：初始化与重置向导 —— 菜单 + 权限（补齐《接口文档》系统模块未实现部分）
-- 设计对齐：接口文档「系统管理」4 个接口（init/status、init、reset/light、reset/full）
-- 幂等：依赖唯一键（sys_menu.id / sys_permission.perm_code / 复合唯一）做 ON DUPLICATE 兜底
-- ============================================================================

-- 1. 菜单：系统管理(12) → 初始化与重置
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`)
VALUES (90, '初始化与重置', 12, '/system/init-reset', 'system/init-reset', 'system:init', 'MENU', 7, 1, 'ENABLED')
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `component` = VALUES(`component`);

-- 2. 权限：初始化 / 重置
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`)
VALUES
('system:init',  '系统初始化', 'API', 'EXECUTE', 'ENABLED'),
('system:reset', '系统重置',   'API', 'EXECUTE', 'ENABLED')
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`);

-- 3. ROLE_ADMIN 拥有这两个权限
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`)
SELECT r.id, p.id, 'ENABLED'
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code IN ('system:init', 'system:reset')
ON DUPLICATE KEY UPDATE `status` = VALUES(`status`);

-- 4. ROLE_ADMIN 拥有新菜单
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.id, 90
FROM `sys_role` r
WHERE r.role_code = 'ROLE_ADMIN'
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
