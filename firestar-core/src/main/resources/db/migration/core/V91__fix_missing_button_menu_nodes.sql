-- ============================================================================
-- V91 修复：补建「父菜单组件漂移」导致从未插入的 BUTTON 菜单节点，并回填授权
-- ============================================================================
--
-- 【症状（虎哥 2026-10-31 实测）】
--   银行回款「银行回单」上传控件、发货「上传签收证据」控件均不渲染；
--   /auth/profile 返回的 permissions 里没有 file:upload；
--   SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = 364 → 0。
--   而 V88 / V89 / V90 三条迁移在 flyway_schema_history 里均 success=1。
--
-- 【根因：不是授权逻辑，是节点压根不存在】
--   V81 建 BUTTON 菜单节点时，父菜单靠 component 字符串 JOIN 定位（V81:208）：
--       JOIN (SELECT component, MIN(id) AS id FROM sys_menu
--              WHERE menu_type='MENU' GROUP BY component) pm
--         ON pm.component = d.parent_component
--   「父菜单不存在则静默跳过该节点」（V81:126-130 注释已预言此坑），
--   而 file:* 四个节点写的父组件是 'system/file'：
--       · V4__init_rbac_seed.sql:75  建 id=18「文件管理」component='system/file'   ← 当时对
--       · V19:157-164  把 id=18 改造为「文件列表」，component 改成 'file/list'      ← 漂移发生
--   V81 执行时（81 > 19）库里已无 component='system/file' 的 MENU
--   → 364/365/366/367 四行从未插入，且无任何报错。
--
--   连带影响（链式）：
--       sys_menu 无 file:upload BUTTON 节点
--     → V88 第 2 段、V90 第 2 段都 JOIN 不到 m，回填 0 行
--     → AuthServiceImpl.loadPermissions（严格分离后只查菜单 BUTTON）拿不到 file:upload
--     → 前端 v-has-perm 判定失败 → 控件 removeChild
--   这也解释了为什么「给角色重新赋一次全量权限」救不回来：
--   授权弹窗的树里根本没有 364 这个节点可勾，提交集自然不含它。
--
-- 【另一处同类漂移】
--   V88 给 system:help 建节点（id=375）时父组件写了 'help/index'，
--   但 V32:104 建的「帮助中心维护」菜单 id=35 的 component 是 'system/help-manage'
--   → 375 同样从未插入。本迁移一并用正确父组件补回。
--   （对照：V88 的 376 system:reset 父组件 'system/reset' 确实存在（V37 改的 id=90），
--     该节点已正常插入，本迁移不重复处理。）
--
-- 【本迁移做两件事】
--   1. 按「当前真实存在的父组件」补建 5 个 BUTTON 节点；
--   2. 重新执行一次通用回填（与 V88 第 2 段同口径），把持有对应操作权限的角色
--      补勾到这些新节点上——否则节点补了、按钮仍然不显示。
--
-- 【幂等性】
--   节点插入走 ON DUPLICATE KEY；回填走 LEFT JOIN ... IS NULL，可重复执行。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 补建缺失的 BUTTON 节点（父组件用当前库中的真实值）
--    id 沿用 V81 原始规划值，避免与既有 301-376 段冲突。
-- ---------------------------------------------------------------------------
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT d.id, d.menu_name, pm.id, '', NULL, d.perm_code, 'BUTTON', d.sort_no, 1, 'ENABLED', 0
FROM (
  -- 文件管理页：父菜单 id=18，component 自 V19 起为 'file/list'
  SELECT 364 AS id, '文件上传' AS menu_name, 'file/list' AS parent_component, 'file:upload'   AS perm_code, 1 AS sort_no
  UNION ALL SELECT 365, '文件下载', 'file/list', 'file:download', 2
  UNION ALL SELECT 366, '文件别名', 'file/list', 'file:alias',    3
  UNION ALL SELECT 367, '文件删除', 'file/list', 'file:delete',   4
  -- 帮助中心维护：父菜单 id=35，component 为 'system/help-manage'（V88 误写 'help/index'）
  UNION ALL SELECT 375, '帮助中心管理', 'system/help-manage', 'system:help', 98
) d
JOIN (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' GROUP BY `component`) pm
  ON pm.`component` = d.parent_component
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

-- ---------------------------------------------------------------------------
-- 2. 通用回填：把「已持有该 perm_code 操作权限」的角色，补勾对应 BUTTON 菜单节点
--    （与 V88 第 2 段同口径；V88 执行时这些节点还不存在，故此处必须再跑一次）
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
