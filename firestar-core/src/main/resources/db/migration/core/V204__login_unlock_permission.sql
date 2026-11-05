-- ===========================================================================
-- 登录日志「解锁账号」按钮权限（爆破防护闭环的运维动作授权）
-- 2026-11-05：登录日志页新增「详情 → 解锁」操作（虎哥要求挂在登录日志菜单下）。
--
-- 设计：解锁是登录日志菜单下的运维动作，复用一个 BUTTON 权限点 log:unlock：
--   ① sys_permission 权限点（API/WRITE）
--   ② sys_menu BUTTON 子节点（parent=29 登录日志，perm_code=log:unlock）
--   ③ ROLE_ADMIN 同时链 sys_role_permission + sys_role_menu（与 V101/V102 同款）
--
-- 依赖：V19(id=29 登录日志菜单, log:view) → V1(ROLE_ADMIN 基线)
--
-- ⚠️ 同 V101 铁律：① id 写死前核对占用（29 为登录日志本身，2901 空闲）；
--   ② 仅 INSERT + ON DUPLICATE KEY UPDATE，不动既有行；
--   ③ 主键 / 唯一约束幂等，重复执行安全。
--
-- 同步项：无新增业务表 / 字典数据 → DatabaseCheckService 计数常量不变。
-- ===========================================================================

-- ========== 1. 权限点 ==========
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('log:unlock', '登录日志-解锁账号', 'API', 'WRITE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`),
                        `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- ========== 2. 菜单按钮（登录日志下，不可见按钮仅作权限挂载点） ==========
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`) VALUES
(2901, '登录日志-解锁账号', 29, '', '', 'log:unlock', 'BUTTON', 1, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`),
                        `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`),
                        `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`);

-- ========== 3. 角色授权（仅 ROLE_ADMIN） ==========
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code IN ('log:unlock')
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id = 2901
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
