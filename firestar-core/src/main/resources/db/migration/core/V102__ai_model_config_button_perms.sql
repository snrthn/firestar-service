-- ============================================================================
-- V102：补 AI 模型配置页「按钮(BUTTON)」菜单节点 + 角色授权（修复添加入口不显示）
-- 根因：V101 只建了 MENU(id=94) 并把它链接到 ROLE_ADMIN(sys_role_menu)，
--       但前端 v-has-perm 的按钮权限码来自「BUTTON 菜单节点 + sys_role_menu」
--       （方案 B-③，见 AuthServiceImpl.loadPermissions：只收 menu_type='BUTTON'
--        且 perm_code 非空的节点），V101 漏建 BUTTON 节点，导致 sys:modelConfig:edit
--       不进前端权限集 —— 新增/编辑/删除/热重载四个按钮全被 v-has-perm 指令移除。
-- 修复：补 4 个 BUTTON 子节点(perm_code=sys:modelConfig:edit) 并链接 ROLE_ADMIN。
--
-- 依赖：V101(id=94, ROLE_ADMIN 已授菜单) / V1(ROLE_ADMIN 基线)。
-- 幂等：sys_menu.id、sys_role_menu(role_id,menu_id) 均有唯一键，重复执行安全。
-- 不改变表结构，DatabaseCheckService.EXPECTED_TABLE_COUNT 不变（仍是 42→45，V100 已含 ai_）。
-- ============================================================================

-- ========== 1. BUTTON 节点（挂在 id=94 下，visible=0 不占侧栏，menu_type=BUTTON） ==========
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(9401, 'AI模型配置-新增',   94, '', '', 'sys:modelConfig:edit', 'BUTTON', 1, 0, 'ENABLED', '', 0),
(9402, 'AI模型配置-编辑',   94, '', '', 'sys:modelConfig:edit', 'BUTTON', 2, 0, 'ENABLED', '', 0),
(9403, 'AI模型配置-删除',   94, '', '', 'sys:modelConfig:edit', 'BUTTON', 3, 0, 'ENABLED', '', 0),
(9404, 'AI模型配置-热重载', 94, '', '', 'sys:modelConfig:edit', 'BUTTON', 4, 0, 'ENABLED', '', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`),
                        `menu_type` = VALUES(`menu_type`), `status` = VALUES(`status`);

-- ========== 2. 角色授权（仅 ROLE_ADMIN，与 V101 一致） ==========
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id IN (9401, 9402, 9403, 9404)
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
