-- ============================================================================
-- V7：字典管理模块配套（status 口径规范化 + 隐藏二级菜单「字典数据」）
-- 背景：
--   ① V2 种子把 sys_dict_type / sys_dict_data 的 status 写成中文「启用」，
--      与 V1 DDL 注释（字典 common_status：ENABLED/DISABLED）口径不一致；
--      已执行的 Flyway 脚本不可改内容，在此新增迁移做数据规范化。
--   ② 字典管理拆两个页面：主页面 system/dict（字典类型，侧栏可见）+
--      隐藏二级页 system/dict-data（字典数据，侧栏不可见但菜单管理页可见可管）。
--      隐藏菜单 visible=0 —— /auth/menus 按 visible=1 过滤，侧栏不渲染；
--      菜单管理页走全量树，能看到并维护它（含显示/停用/排序）。
-- 幂等：UPDATE 自带幂等；INSERT 带 ON DUPLICATE KEY。
-- 依赖：V1(baseline 建表) → V2(dict 种子) → V4(rbac 种子，id<=18) → V6(分组，id 19-21)
-- ============================================================================

-- 1. status 口径规范化：中文「启用/停用」→ ENABLED/DISABLED（对齐 common_status 字典值）
--    只影响历史中文值，已是英文编码的行不受影响；重复执行结果一致。
UPDATE `sys_dict_type` SET `status` = 'ENABLED'  WHERE `status` = '启用';
UPDATE `sys_dict_type` SET `status` = 'DISABLED' WHERE `status` = '停用';
UPDATE `sys_dict_data` SET `status` = 'ENABLED'  WHERE `status` = '启用';
UPDATE `sys_dict_data` SET `status` = 'DISABLED' WHERE `status` = '停用';

-- 2. 隐藏二级菜单：字典数据（挂在字典管理 id=17 下，sort_no=1）
--    route_path / component 与前端路由表对齐；perm_code 复用 dict:manage
--    （隐藏页不单设权限点，沿用字典管理模块权限，RuoYi 按钮级权限惯例）。
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`) VALUES
(22, '字典数据', 17, '/system/dict-data', 'system/dict-data', 'dict:manage', 'MENU', 1, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`);

-- 3. 给隐藏菜单补授 ROLE_ADMIN（V4 授权只覆盖 id<=18，V6 补了 19-21，这里补 22）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id = 22
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
