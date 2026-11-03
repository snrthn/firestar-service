-- ============================================================================
-- V101：AI 模型配置页菜单 + 权限点（虎哥 2026-11-02 拍板，配合 AI 助手集成）
--   1. 权限点 2 个：sys:modelConfig:view（页面访问）/ sys:modelConfig:edit（变更）
--   2. 菜单 1 个：id=94，挂在系统管理(id=12) 下，可见，组件 system/model-config
--   3. 角色授权：仅 ROLE_ADMIN 拥有（配置页为管理员维护项，普通用户无入口/无权限）
--
-- 依赖：V1（角色基线，ROLE_ADMIN 已存在）→ V12（系统管理 parent_id=12）
--       V99 sys_model_config（表）/ V100 ai_conversations 等（会话表，本脚本不动表）
--
-- ⚠️ 血案铁律逐条遵守（同 V32）：
-- ① 菜单 id 写死前核对：已占用 1–93（V90–V98 在 90+，V32 用 34/35/36，本次取 94，空闲）。
-- ② 系统管理(id=12) 下 sort_no 1–13 已占（V32 用 11/12/13）→ 本次取 14。
-- ③ 只 INSERT 新行 + ON DUPLICATE KEY UPDATE，不 UPDATE 既有行。
-- ④ 主键 / 唯一约束幂等：sys_permission.perm_code、sys_menu.id、sys_role_menu(role_id,menu_id)、
--    sys_role_permission(role_id,permission_id) 均有唯一键，重复执行安全。
-- ⑤ 表前缀约定：本脚本只动 sys_ 表，不新增表，故 DatabaseCheckService.EXPECTED_TABLE_COUNT 不变（仍是 42）。
--
-- 同步项：无（未新增业务表，不改变 EXPECTED_TABLE_COUNT）。
-- ============================================================================

-- ========== 1. 权限点 ==========
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('sys:modelConfig:view', 'AI模型配置查看', 'API', 'READ',  'ENABLED', 0),
('sys:modelConfig:edit', 'AI模型配置变更', 'API', 'WRITE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`), `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- ========== 2. 菜单（系统管理下，可见） ==========
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(94, 'AI模型配置', 12, '/system/model-config', 'system/model-config', 'sys:modelConfig:view', 'MENU', 14, 1, 'ENABLED', 'Setting', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`), `icon` = VALUES(`icon`);

-- ========== 3. 角色授权（仅 ROLE_ADMIN） ==========
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code IN ('sys:modelConfig:view', 'sys:modelConfig:edit')
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id = 94
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
