-- ============================================================================
-- V219：历史数据导入权限点（方案 v3，迭代 1）
-- ----------------------------------------------------------------------------
-- db-check-baseline: table=53, dictType=40, dictData=179, config=14, role=9, roleConflict=4
--   （本迁移只新增权限点与 BUTTON 菜单、给 ROLE_ADMIN 授权，不新增角色/字典/配置/业务表，
--      六项累计总数与 V218 后基线一致；按护栏要求声明以通过 DatabaseCheckSeedBaselineGuardTest）
-- 背景：P0(5)+P1(4) 共 9 类导入 + 批次视图/归档 + 客户/货物主数据管理。
--   双线落库（与 V96 同范式）：
--     ① sys_permission(API) + sys_role_permission  → 后端 @RequirePerm 拦截
--     ② sys_menu(BUTTON, perm_code) + sys_role_menu → 前端 v-has-perm 展示集
--   只授权 ROLE_ADMIN（导入是管理员/实施角色的系统能力，普通业务角色不开放）。
--
-- 菜单按钮无独立页面：parent 取 project/list 菜单 id（客户/货物入口在项目页），
-- 若该菜单缺失则防御式静默跳过（不影响后端 API 权限）。
-- 幂等：全部 INSERT ... SELECT + ON DUPLICATE KEY UPDATE。
-- ============================================================================

-- ---------- 1. sys_permission（API 权限点，12 个） ----------
INSERT INTO `sys_permission`
    (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`)
VALUES
    ('import:view',       '导入批次查看',   'API', 'READ',   'ENABLED', 0),
    ('import:archive',    '导入批次归档',   'API', 'WRITE',  'ENABLED', 0),
    ('import:customer',   '客户档案导入',   'API', 'WRITE',  'ENABLED', 0),
    ('import:goods',      '货物档案导入',   'API', 'WRITE',  'ENABLED', 0),
    ('import:project_opening',    '期初项目导入',   'API', 'WRITE', 'ENABLED', 0),
    ('import:receivable_opening', '期初应收导入',   'API', 'WRITE', 'ENABLED', 0),
    ('import:user_opening',       '用户导入',       'API', 'WRITE', 'ENABLED', 0),
    ('import:org_opening',        '组织导入',       'API', 'WRITE', 'ENABLED', 0),
    ('import:receipt_opening',    '期初收款导入',   'API', 'WRITE', 'ENABLED', 0),
    ('import:invoice_opening',    '期初发票导入',   'API', 'WRITE', 'ENABLED', 0),
    ('import:shipment_opening',   '期初发货导入',   'API', 'WRITE', 'ENABLED', 0),
    ('customer:manage',   '客户档案管理',   'API', 'WRITE',  'ENABLED', 0),
    ('goods:manage',      '货物档案管理',   'API', 'WRITE',  'ENABLED', 0)
ON DUPLICATE KEY UPDATE
    `perm_name` = VALUES(`perm_name`),
    `status`    = 'ENABLED';

-- ---------- 2. sys_role_permission 授权 ROLE_ADMIN ----------
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN'
  AND p.perm_code IN ('import:view','import:archive','import:customer','import:goods',
                      'import:project_opening','import:receivable_opening','import:user_opening',
                      'import:org_opening','import:receipt_opening','import:invoice_opening',
                      'import:shipment_opening','customer:manage','goods:manage')
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

-- ---------- 3. sys_menu BUTTON 节点（前端 v-has-perm 展示集） ----------
-- id 用 400-412（现有最大约 383，承接 V96，无冲突）；挂 project/list 下。
INSERT INTO `sys_menu`
    (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 400, '导入批次查看', pm.id, '', NULL, 'import:view', 'BUTTON', 1, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' GROUP BY `component`) pm
WHERE pm.`component` = 'project/list'
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

INSERT INTO `sys_menu`
    (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 401, '导入批次归档', pm.id, '', NULL, 'import:archive', 'BUTTON', 2, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' GROUP BY `component`) pm
WHERE pm.`component` = 'project/list'
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

INSERT INTO `sys_menu`
    (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 402, '客户档案管理', pm.id, '', NULL, 'customer:manage', 'BUTTON', 3, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' GROUP BY `component`) pm
WHERE pm.`component` = 'project/list'
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

INSERT INTO `sys_menu`
    (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 403, '货物档案管理', pm.id, '', NULL, 'goods:manage', 'BUTTON', 4, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' GROUP BY `component`) pm
WHERE pm.`component` = 'project/list'
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

-- 9 个类型导入按钮：id 404-412，sort 5-13
INSERT INTO `sys_menu`
    (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 404, '客户档案导入', pm.id, '', NULL, 'import:customer', 'BUTTON', 5, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' AND `component`='project/list') pm
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 405, '货物档案导入', pm.id, '', NULL, 'import:goods', 'BUTTON', 6, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' AND `component`='project/list') pm
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 406, '期初项目导入', pm.id, '', NULL, 'import:project_opening', 'BUTTON', 7, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' AND `component`='project/list') pm
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 407, '期初应收导入', pm.id, '', NULL, 'import:receivable_opening', 'BUTTON', 8, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' AND `component`='project/list') pm
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 408, '用户导入', pm.id, '', NULL, 'import:user_opening', 'BUTTON', 9, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' AND `component`='project/list') pm
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 409, '组织导入', pm.id, '', NULL, 'import:org_opening', 'BUTTON', 10, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' AND `component`='project/list') pm
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 410, '期初收款导入', pm.id, '', NULL, 'import:receipt_opening', 'BUTTON', 11, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' AND `component`='project/list') pm
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 411, '期初发票导入', pm.id, '', NULL, 'import:invoice_opening', 'BUTTON', 12, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' AND `component`='project/list') pm
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 412, '期初发货导入', pm.id, '', NULL, 'import:shipment_opening', 'BUTTON', 13, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' AND `component`='project/list') pm
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

-- ---------- 4. sys_role_menu 授权 ROLE_ADMIN ----------
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN'
  AND m.perm_code IN ('import:view','import:archive','customer:manage','goods:manage',
                      'import:customer','import:goods','import:project_opening',
                      'import:receivable_opening','import:user_opening','import:org_opening',
                      'import:receipt_opening','import:invoice_opening','import:shipment_opening')
  AND m.menu_type = 'BUTTON'
ON DUPLICATE KEY UPDATE `menu_id` = VALUES(`menu_id`);
