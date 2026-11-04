-- ============================================================================
-- V96：发票「开票」按钮权限点（BUTTON 节点 + 授权 ROLE_ADMIN / ROLE_INVOICER）
-- ----------------------------------------------------------------------------
-- 背景（虎哥 2026-10-31 拍板，V95 发票流程重构配套）：
--   流程拆分为 申请(DRAFT) → 审批通过(SUBMITTED 待开票) → 开票(ISSUED)。
--   开票是独立节点，由开票员（ROLE_INVOICER）在发票列表点「开票」按钮，
--   填报真实税务发票号/代码/日期并上传电子票（POST /invoice/{id}/issue）。
--   该按钮需受 v-has-perm="'invoice:issue'" 控制，故补一个 BUTTON 菜单节点进展示集。
--
-- 严格分离链路（见 AuthServiceImpl.loadPermissions）：
--   v-has-perm 的展示集 = sys_menu(menu_type='BUTTON' 且 perm_code 非空)
--                        经 sys_role_menu 分配后的 perm_code 集合。
--   invoice:issue 此前仅作为 sys_permission(API/WRITE) 由 @RequirePerm 保护接口，
--   不在展示集，直接给按钮挂 v-has-perm 会使按钮对所有人（含 admin）隐藏。
--   故新建同名 BUTTON 节点，与「发票编辑」(id=309, invoice:edit) 同挂 invoice/list 下。
-- ============================================================================

-- 1. BUTTON 菜单节点（防御式：父菜单 invoice/list 缺失则静默跳过，不报错）
--    id 用 383（现有最大菜单 id=382，承接 V94，无冲突）；component 为空（按钮无独立页面）
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 383, '开票', pm.id, '', NULL, 'invoice:issue', 'BUTTON', 3, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' GROUP BY `component`) pm
WHERE pm.`component` = 'invoice/list'
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

-- 2. 授权 ROLE_ADMIN（admin 默认拥有全部按钮权限点，存量不丢按钮）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN'
  AND m.perm_code = 'invoice:issue'
  AND m.menu_type = 'BUTTON'
ON DUPLICATE KEY UPDATE `menu_id` = VALUES(`menu_id`);

-- 3. 授权 ROLE_INVOICER（开票员，本按钮的实际操作角色）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_INVOICER'
  AND m.perm_code = 'invoice:issue'
  AND m.menu_type = 'BUTTON'
ON DUPLICATE KEY UPDATE `menu_id` = VALUES(`menu_id`);
