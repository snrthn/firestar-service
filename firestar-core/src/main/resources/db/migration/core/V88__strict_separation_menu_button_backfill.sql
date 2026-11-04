-- ============================================================================
-- V88 严格分离（方案 B-③）：菜单 BUTTON 节点补齐 + 存量角色回填
-- ============================================================================
--
-- 【背景：为什么必须有这条迁移】
--   方案 B-③ 严格分离（2026-10-30 虎哥拍板）把两条权限线彻底拆开：
--     · 按钮展示 → 只认【菜单权限】：sys_role_menu → sys_menu(BUTTON, perm_code)
--       （见 AuthServiceImpl.loadPermissions，已移除 sys_role_permission 那条来源）
--     · 接口放行 → 只认【操作权限】：sys_role_permission → sys_permission
--       （见 PermissionResolverImpl.resolve，已移除菜单 BUTTON 那条来源）
--   而 V81 只【建了 BUTTON 菜单节点】，却从未向 sys_role_menu 插过任何一行
--   （全量迁移内 grep sys_role_menu 无 BUTTON 相关 INSERT）。
--   → 若不回填，切换后所有角色的按钮会【集体消失】，这是不可接受的破坏性变更。
--   故本迁移负责把存量角色的「操作权限」等价回填为「菜单 BUTTON 勾选」。
--
-- 【回填规则】
--   角色持有某 perm_code 的操作权限（sys_role_permission）
--   → 且 sys_menu 中存在 perm_code 同名、menu_type='BUTTON' 的节点
--   → 则 INSERT 一行 sys_role_menu(role_id, menu_id, tenant_id)。
--   只补不删，LEFT JOIN ... IS NULL 去重，可重复执行。
--
-- 【幂等性】
--   LEFT JOIN 目标表用 IS NULL 排除已存在行（不用 NOT EXISTS 子查询，规避 MySQL 1093：
--   INSERT ... SELECT 的目标表不能直接出现在子查询 FROM 里）。
--   sys_role_menu 唯一键 uk_role_menu(tenant_id, role_id, menu_id) 兜底。
--
-- 【已知遗留：agreement:publish】
--   协议管理页 agreement-manage.vue 是孤儿页，sys_menu 里没有它的菜单记录
--   （V81 注释第 19-20 行已注明"未挂菜单，无父菜单可挂，故不建 BUTTON 节点"）。
--   → 本迁移不为它建 BUTTON 节点，严格分离后该按钮将不显示。
--   若日后给协议管理页补建菜单（component 记为 C），补节点后重跑本迁移的第 2 段即可回填。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 补齐缺失的 BUTTON 菜单节点（前端有按钮、菜单树无节点 → 严格分离后会消失）
--    父菜单按 component 动态定位（沿用 V81 定型写法：INSERT...SELECT + JOIN 派生表，
--    父菜单不存在则静默跳过该节点，永不产生 NULL parent_id）。
--    id 从 375 起（V81 用到 374）。
-- ---------------------------------------------------------------------------
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT d.id, d.menu_name, pm.id, '', NULL, d.perm_code, 'BUTTON', d.sort_no, 1, 'ENABLED', 0
FROM (
  SELECT 375 AS id, '帮助中心管理' AS menu_name, 'help/index'   AS parent_component, 'system:help'  AS perm_code, 98 AS sort_no
  UNION ALL SELECT 376, '系统重置',   'system/reset', 'system:reset', 98
) d
JOIN (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' GROUP BY `component`) pm
  ON pm.`component` = d.parent_component
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

-- ---------------------------------------------------------------------------
-- 2. 存量回填：把「已持有该 perm_code 操作权限」的角色，补勾对应 BUTTON 菜单节点
-- ---------------------------------------------------------------------------
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT DISTINCT rp.role_id, m.id, rp.tenant_id
FROM `sys_role_permission` rp
         JOIN `sys_permission` p
              ON p.id = rp.permission_id
         JOIN `sys_menu` m
              ON m.perm_code = p.perm_code
                  AND m.menu_type = 'BUTTON'
         LEFT JOIN `sys_role_menu` x
              ON x.role_id = rp.role_id
                  AND x.menu_id = m.id
                  AND x.tenant_id = rp.tenant_id
WHERE x.id IS NULL
ON DUPLICATE KEY UPDATE `tenant_id` = `sys_role_menu`.`tenant_id`;
