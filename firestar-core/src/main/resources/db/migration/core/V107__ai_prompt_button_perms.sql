-- ============================================================================
-- V107：补 AI 提示词管理页「按钮(BUTTON)」菜单节点 + 角色授权（修复添加入口不显示）
-- 根因：V106 只建了 MENU(id=9502) + 操作权限(sys_permission/sys_role_permission)，
--       但前端 v-has-perm 的按钮权限码来自「BUTTON 菜单节点 + sys_role_menu」
--       （方案 B-③，见 AuthServiceImpl.loadPermissions：只收 menu_type='BUTTON'
--        且 perm_code 非空的节点），V106 漏建 BUTTON 节点，导致 ai:prompt:edit /
--        ai:prompt:use 不进前端权限集 —— 新增/编辑/删除/启用四个按钮全被
--        v-has-perm 指令移除（页面侧栏可见但按钮全无，与 V101→V102 同一坑）。
-- 修复：补 4 个 BUTTON 子节点（新增/编辑/删除=ai:prompt:edit，启用=ai:prompt:use）
--       并链接 ROLE_ADMIN。后端 @RequirePerm 所需的 sys_role_permission 已由 V106 建好，
--       本脚本只补全前端展示这半条线。
--
-- 依赖：V106(id=9502, ROLE_ADMIN 已授菜单 + 操作权限) / V1(ROLE_ADMIN 基线)。
-- 幂等：sys_menu.id、sys_role_menu(role_id,menu_id) 均有唯一键，重复执行安全。
-- 不改变表结构，DatabaseCheckService.EXPECTED_TABLE_COUNT 不变（仍是 46）。
-- id 核对：95021~95024 经全量 migration grep 确认空闲（无其它 V 占用）。
-- ============================================================================

-- ========== 1. BUTTON 节点（挂在 id=9502 下，visible=0 不占侧栏，menu_type=BUTTON） ==========
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(95021, 'AI提示词-新增', 9502, '', '', 'ai:prompt:edit', 'BUTTON', 1, 0, 'ENABLED', '', 0),
(95022, 'AI提示词-编辑', 9502, '', '', 'ai:prompt:edit', 'BUTTON', 2, 0, 'ENABLED', '', 0),
(95023, 'AI提示词-删除', 9502, '', '', 'ai:prompt:edit', 'BUTTON', 3, 0, 'ENABLED', '', 0),
(95024, 'AI提示词-启用', 9502, '', '', 'ai:prompt:use',  'BUTTON', 4, 0, 'ENABLED', '', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`),
                        `menu_type` = VALUES(`menu_type`), `status` = VALUES(`status`);

-- ========== 2. 角色授权（仅 ROLE_ADMIN，与 V106 一致） ==========
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id IN (95021, 95022, 95023, 95024)
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
