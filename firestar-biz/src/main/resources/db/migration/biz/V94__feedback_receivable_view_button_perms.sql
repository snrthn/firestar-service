-- ============================================================================
-- V94：反馈/应收列表「详情」按钮权限点（BUTTON 节点 + 默认授权）
-- ----------------------------------------------------------------------------
-- 背景：feedback/list 与 receivable/list 的操作列只有一个「详情」按钮，此前未挂
--   任何权限，故在前一轮「操作列+按钮权限统一处理」时被排除。本迁移补齐，使其
--   纳入「列级隐藏」逻辑（角色无该查看权限时整列收起）。
--
-- 关键链路（严格分离，见 AuthServiceImpl.loadPermissions）：
--   v-has-perm 的展示集 = sys_menu(menu_type='BUTTON' 且 perm_code 非空)
--                       经 sys_role_menu 分配后的 perm_code 集合。
--   接口鉴权集 = sys_role_permission → sys_permission（与上面两套数据互不相干）。
--
-- 两模块差异：
--   ① 反馈详情 /feedback/{id} 接口层【无 @RequirePerm】（业务层按 currentUserId/isAdmin
--      判断可见性），故 feedback:view 仅作前端展示控制节点即可，无需在 sys_permission 建 API 码。
--   ② 应收详情 /receivable/{id} 受类级 @RequirePerm("receivable:view") 罩着，
--      receivable:view 已在 sys_permission（API/WRITE，V30 起）。此处仅补【同名 BUTTON 菜单节点】
--      进展示集，不影响接口鉴权（两套数据各自独立、互不冲突）。
--
-- 分配策略：默认给【全部角色】授权（含 ROLE_ADMIN）。理由：两模块的「详情」属查看类操作，
--   进入页面即应可见，隐藏列默认不触发，保持现有体验；未来可按需撤销某角色的该 BUTTON
--   节点，使其操作列自动整列收起。
-- ============================================================================

-- 1. BUTTON 节点：反馈详情（挂 system/feedback 下，与「反馈创建」按钮同级）
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 381, '反馈详情', pm.id, '', NULL, 'feedback:view', 'BUTTON', 2, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' GROUP BY `component`) pm
WHERE pm.`component` = 'system/feedback'
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

-- 1b. BUTTON 节点：应收详情（挂 receivable/list 下，与「应收导出」按钮同级）
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 382, '应收详情', pm.id, '', NULL, 'receivable:view', 'BUTTON', 2, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' GROUP BY `component`) pm
WHERE pm.`component` = 'receivable/list'
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

-- 2. 默认授权 ROLE_ADMIN（admin 自动拥有全部按钮权限点）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN'
  AND m.perm_code IN ('feedback:view', 'receivable:view')
  AND m.menu_type = 'BUTTON'
ON DUPLICATE KEY UPDATE `menu_id` = VALUES(`menu_id`);

-- 3. 默认授权全部角色（查看类操作，进入页面即应可见；隐藏列默认不触发）
--    派生表包裹 sys_role 规避 MySQL 1093（INSERT 目标表不可直接出现在子查询 FROM）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE m.perm_code IN ('feedback:view', 'receivable:view')
  AND m.menu_type = 'BUTTON'
ON DUPLICATE KEY UPDATE `menu_id` = VALUES(`menu_id`);
