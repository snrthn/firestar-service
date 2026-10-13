-- ============================================================================
-- V6：菜单按业务场景分组（把原先裸奔在顶层的业务模块收纳进场景目录）
-- 背景：V4(init_rbac_seed) 把 8 个业务菜单（发货/应收/收款/发票/核销/退货/审批/数据看板）
--       直接挂在根下，侧边栏显得散乱。本脚本新增 3 个场景 CATALOG 作为分组容器，
--       按业务场景把它们收纳进去；工作台 / 系统管理 / 项目管理 三项保持不动。
-- 分组口径（按财务业务场景自动收纳）：
--   - 财务中心（id 19）：应收、收款、发票、核销   —— 资金收付全流程
--   - 业务中心（id 20）：发货、退货               —— 货物流转
--   - 运营中心（id 21）：审批中心、数据看板       —— 流程审批 + 数据监控
-- 幂等：新增项带 ON DUPLICATE KEY；挂接用 UPDATE，重复执行结果一致。
-- ============================================================================

-- 1. 新增 3 个场景目录（CATALOG，无路由/无组件/无权限点）
--    top-level 排序：工作台(1) / 项目管理(2) / 财务中心(3) / 业务中心(4) / 运营中心(5) / 系统管理(11)
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`) VALUES
(19, '财务中心', 0, '', '', NULL, 'CATALOG', 3, 1, 'ENABLED', 0),
(20, '业务中心', 0, '', '', NULL, 'CATALOG', 4, 1, 'ENABLED', 0),
(21, '运营中心', 0, '', '', NULL, 'CATALOG', 5, 1, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`);

-- 2. 重新挂接业务菜单到对应场景目录，并理顺组内排序
--    财务中心：应收(1) / 收款(2) / 发票(3) / 核销(4)
UPDATE `sys_menu` SET `parent_id` = 19, `sort_no` = 1 WHERE `id` = 5;  -- 应收管理
UPDATE `sys_menu` SET `parent_id` = 19, `sort_no` = 2 WHERE `id` = 6;  -- 收款管理
UPDATE `sys_menu` SET `parent_id` = 19, `sort_no` = 3 WHERE `id` = 7;  -- 发票管理
UPDATE `sys_menu` SET `parent_id` = 19, `sort_no` = 4 WHERE `id` = 8;  -- 核销管理
--    业务中心：发货(1) / 退货(2)
UPDATE `sys_menu` SET `parent_id` = 20, `sort_no` = 1 WHERE `id` = 4;  -- 发货管理
UPDATE `sys_menu` SET `parent_id` = 20, `sort_no` = 2 WHERE `id` = 9;  -- 退货管理
--    运营中心：审批中心(1) / 数据看板(2)
UPDATE `sys_menu` SET `parent_id` = 21, `sort_no` = 1 WHERE `id` = 10; -- 审批中心
UPDATE `sys_menu` SET `parent_id` = 21, `sort_no` = 2 WHERE `id` = 11; -- 数据看板

-- 3. 给新增场景目录授予 ROLE_ADMIN
--    V4 的授权语句只在执行时覆盖当时已存在的菜单（id<=18），新增的 19/20/21 需补授。
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id IN (19, 20, 21)
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
