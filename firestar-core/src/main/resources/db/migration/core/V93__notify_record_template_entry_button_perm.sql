-- ============================================================================
-- V93：通知记录页「通知模板」按钮权限点（BUTTON 节点 + 默认授权）
-- ----------------------------------------------------------------------------
-- 背景：通知记录页（system/notify-record）头部「通知模板」按钮原未挂任何权限，
--   任何人（含无模板管理权限的角色）都可见、可一键跳转到模板管理页。
--
-- 关键链路（严格分离，见 AuthServiceImpl.loadPermissions）：
--   v-has-perm 的展示集 = sys_menu(menu_type='BUTTON' 且 perm_code 非空)
--                       经 sys_role_menu 分配后的 perm_code 集合。
--   而 notify:template 在库中是【页面级 MENU 节点 id=32】+【sys_permission(API/WRITE)】
--   （V30 建立），二者都【不在】上述 BUTTON 展示集里。若直接给按钮挂
--   v-has-perm="'notify:template'" 而不建 BUTTON 节点，按钮会对所有人（含 admin）隐藏。
--
-- 因此本迁移：① 新建一个 perm_code='notify:template' 的 BUTTON 菜单节点
--            （挂到「通知记录」页 system/notify-record 下，与「通知发布」按钮同级）；
--            ② 默认授权 ROLE_ADMIN；③ 兼容回填给已能编辑模板的非 admin 角色。
-- 说明：sys_permission 中 notify:template 已由 V30 建立，此处【不重复插入】，仅补菜单节点 + 授权。
-- ============================================================================

-- 1. BUTTON 菜单节点（防御式：父菜单 system/notify-record 缺失则静默跳过，不报错）
--    id 用 380（现有最大菜单 id=376，无冲突）；route_path/component 为空（按钮无独立页面）
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 380, '进入通知模板', pm.id, '', NULL, 'notify:template', 'BUTTON', 2, 1, 'ENABLED', 0
FROM (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' GROUP BY `component`) pm
WHERE pm.`component` = 'system/notify-record'
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

-- 2. 默认授权 ROLE_ADMIN（默认打开：admin 自动拥有全部按钮权限点，存量不丢按钮）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN'
  AND m.perm_code = 'notify:template'
  AND m.menu_type = 'BUTTON'
ON DUPLICATE KEY UPDATE `menu_id` = VALUES(`menu_id`);

-- 3. 兼容非 admin 角色：把该入口按钮授权给「已持有通知模板编辑权限」的角色
--    （能编辑模板的角色理应也能进入模板页；此回填与 V88 把操作权限回填成菜单按钮同思路）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT DISTINCT x.role_id, m.id, 0
FROM (
    SELECT DISTINCT rm.role_id
    FROM `sys_role_menu` rm
    JOIN `sys_menu` mm ON mm.id = rm.menu_id
    WHERE mm.perm_code = 'notify:template:edit'
      AND mm.menu_type = 'BUTTON'
) x
JOIN `sys_menu` m ON m.perm_code = 'notify:template' AND m.menu_type = 'BUTTON'
ON DUPLICATE KEY UPDATE `menu_id` = VALUES(`menu_id`);
