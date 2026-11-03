-- ============================================================================
-- V106：AI 提示词管理权限 + 菜单（AI中心 id=95 下，与模型配置 94 并列）
--
-- 目标权限树（系统管理 id=12 → AI中心 id=95 下）：
--   系统管理(12)
--   └─ AI中心(95, CATALOG)
--      ├─ AI助手入口(9501, BUTTON, ai:assistant:use)
--      ├─ AI模型配置(94,   MENU,   sys:modelConfig:view)
--      └─ AI提示词管理(9502, MENU, ai:prompt:view)   ← 本次新增
--
-- 权限码（单一模块三段式）：
--   ai:prompt:view → 查看/列表（菜单 + 列表接口）
--   ai:prompt:edit → 增/改/删/热重载
--   ai:prompt:use  → 启用某套为生效（敏感：影响线上对话行为）
--
-- 依赖：V95(AI中心父节点) / V101(94) / V103(9501)。
-- 幂等：sys_permission.perm_code、sys_menu.id、sys_role_permission、sys_role_menu
--       均有唯一键，重复执行安全。不改变表结构，EXPECTED_TABLE_COUNT 不变。
-- id 核对：9502 经全量 migration grep 确认空闲（无其它 V 占用）。
-- ============================================================================

-- ========== 1. 操作权限点（接口放行用） ==========
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('ai:prompt:view', 'AI提示词查看', 'API', 'READ', 'ENABLED', 0),
('ai:prompt:edit', 'AI提示词编辑', 'API', 'WRITE', 'ENABLED', 0),
('ai:prompt:use', 'AI提示词启用', 'API', 'EXECUTE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`), `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- ========== 2. 提示词管理菜单（AI中心下，MENU，侧栏可见） ==========
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(9502, 'AI提示词管理', 95, 'system/prompt-config', 'system/prompt-config', 'ai:prompt:view', 'MENU', 2, 1, 'ENABLED', 'Document', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`), `icon` = VALUES(`icon`);

-- ========== 3. 角色授权（仅 ROLE_ADMIN） ==========
-- 3.1 操作权限：ai:prompt:* → ROLE_ADMIN（后端 @RequirePerm 放行）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code IN ('ai:prompt:view', 'ai:prompt:edit', 'ai:prompt:use')
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

-- 3.2 菜单权限：9502 → ROLE_ADMIN（前端侧栏显隐）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id = 9502
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
