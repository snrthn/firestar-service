-- ============================================================================
-- V103：AI 助手入口按钮权限 + 挂到新建「AI 中心」父节点（虎哥 2026-11-03 拍板）
--
-- 目标权限树（系统管理 id=12 下）：
--   系统管理(12)
--   └─ AI 中心(CATALOG, id=95)            ← 新建容器，归拢 AI 相关入口
--      ├─ AI 助手入口(BUTTON, ai:assistant:use)   ← 控悬浮按钮 + 后端 chat/stream、latest-draft 接口
--      └─ AI 模型配置(MENU, sys:modelConfig:view, id=94) ← 由 parent=12 下移到 parent=95（作兄弟节点）
--         └─ 新增/编辑/删除/热重载(BUTTON, sys:modelConfig:edit)  ← V102 已建，不动
--
-- 单一权限码 ai:assistant:use 同时管两件事（方案 B-③ 严格分离，双线落库）：
--   ① 按钮展示线：sys_menu(BUTTON,9501, perm_code=ai:assistant:use) + sys_role_menu(ROLE_ADMIN)
--      → 前端 <AiAssistantFab v-has-perm="'ai:assistant:use'"/> 据此显隐悬浮按钮；
--   ② 接口操作线：sys_permission(ai:assistant:use) + sys_role_permission(ROLE_ADMIN)
--      → 后端 @RequirePerm("ai:assistant:use") 据此放行 chat/stream 与 latest-draft。
--   两条线各管一摊，不再 union（与 V102 注释一致）。
--
-- 依赖：V1(ROLE_ADMIN 基线) / V4(id=12 系统管理, CATALOG) / V101(id=94) / V102(94 的按钮)。
-- 幂等：sys_permission.perm_code、sys_menu.id、sys_role_permission(role_id,permission_id)、
--       sys_role_menu(role_id,menu_id) 均有唯一键，重复执行安全。
-- 不改变表结构，DatabaseCheckService.EXPECTED_TABLE_COUNT 不变。
-- id 核对：V101 占 1–94（取 94），95 / 9501 经全量 migration grep 确认空闲。
-- ============================================================================

-- ========== 1. 操作权限点（接口放行用） ==========
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('ai:assistant:use', 'AI助手入口', 'API', 'READ', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`), `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- ========== 2. AI 中心容器节点（系统管理下，CATALOG，侧栏可见） ==========
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(95, 'AI中心', 12, '', '', NULL, 'CATALOG', 15, 1, 'ENABLED', 'Cpu', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`), `icon` = VALUES(`icon`);

-- ========== 3. 模型配置菜单下移挂靠 AI 中心（id=94 不变，仅改 parent_id） ==========
UPDATE `sys_menu` SET `parent_id` = 95 WHERE `id` = 94;

-- ========== 4. AI 助手入口 BUTTON 节点（悬浮按钮，侧栏不可见 visible=0） ==========
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(9501, 'AI助手入口', 95, '', '', 'ai:assistant:use', 'BUTTON', 1, 0, 'ENABLED', '', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `status` = VALUES(`status`);

-- ========== 5. 角色授权（仅 ROLE_ADMIN） ==========
-- 5.1 操作权限：ai:assistant:use → ROLE_ADMIN（后端 @RequirePerm 放行）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code = 'ai:assistant:use'
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

-- 5.2 按钮/菜单权限：9501 → ROLE_ADMIN（前端 v-has-perm 显隐悬浮按钮）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id = 9501
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
