-- ============================================================================
-- V8：组织架构管理模块配套（菜单 + 权限种子 + 授权；不种部门数据）
-- 背景：org 模块（/system/org/**）后端 API 已就绪，前端页面随后注册。
--       部门数据由使用方在页面上自行添加，本脚本只铺菜单与权限骨架。
-- 内容：
--   ① 新增权限点 org:manage（模块级粗粒度，同 dict:manage 惯例）；
--   ② 系统管理(id=12)下新增「组织架构」菜单(id=23)，sort_no 定为 1，
--      原 1–6（用户/角色/权限/菜单/字典/文件）顺延为 2–7；
--   ③ 给 ROLE_ADMIN 补授新权限 + 新菜单（V4 授权只覆盖执行时已存在的行）。
-- 幂等：INSERT 带 ON DUPLICATE KEY；UPDATE 重排重复执行结果一致。
-- 依赖：V1(baseline 建表) → V4(rbac 种子，菜单 id<=18) → V6(分组 19-21)
--       → V7(字典管理，菜单 id=22)
-- ============================================================================

-- 1. 权限点：组织架构管理（模块级 WRITE，与 rbac:* / dict:manage 同口径）
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('org:manage', '组织架构管理', 'API', 'WRITE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`), `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- 2. 组织架构菜单（挂在系统管理 id=12 下，sort_no=1 占位，visible=1 侧栏可见）
--    route_path / component 与前端路由表对齐（router 注册 systemOrg）
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`) VALUES
(23, '组织架构', 12, '/system/org', 'system/org', 'org:manage', 'MENU', 1, 1, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`);

-- 3. 系统管理组内排序重排：组织架构占 1，原 1–6 顺延 2–7
--    （id 22 字典数据是 id 17 的子菜单，不参与本组排序）
UPDATE `sys_menu` SET `sort_no` = 2 WHERE `id` = 13; -- 用户管理
UPDATE `sys_menu` SET `sort_no` = 3 WHERE `id` = 14; -- 角色管理
UPDATE `sys_menu` SET `sort_no` = 4 WHERE `id` = 15; -- 权限管理
UPDATE `sys_menu` SET `sort_no` = 5 WHERE `id` = 16; -- 菜单管理
UPDATE `sys_menu` SET `sort_no` = 6 WHERE `id` = 17; -- 字典管理
UPDATE `sys_menu` SET `sort_no` = 7 WHERE `id` = 18; -- 文件管理

-- 4. 给 ROLE_ADMIN 补授新权限点（V4 第 5 段只覆盖当时已有权限行）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code = 'org:manage'
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

-- 5. 给 ROLE_ADMIN 补授新菜单（同 V4 第 6 段 / V6 第 3 段惯例）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id = 23
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
