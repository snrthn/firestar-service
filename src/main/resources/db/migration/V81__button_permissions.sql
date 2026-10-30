-- ============================================================================
-- V81：按钮级权限点（任务3）
-- 目的：为各业务/系统页面的「写/状态变更」按钮建立细粒度权限点，
--       挂到对应页面菜单下（BUTTON 类型节点，仅权限管理 UI 组织展示），
--       并默认授权 ROLE_ADMIN（默认打开，存量用户不丢按钮）。
-- 说明：
--   - 权限集来源 = sys_role_permission → sys_permission（前端 hasPermission 与后端
--     @RequirePerm 鉴权同源，与菜单 perm_code 字段无关），故此处只需建权限点 + 授权。
--   - 前端按钮用 v-has-perm="'xxx:create'" 控制显隐；后端写接口后续补
--     @RequirePerm("xxx:create") 做真·按动作拦截（本迁移只负责权限点 + 默认开放）。
--   - BUTTON 节点的 parent_id 由父菜单 component 动态定位（不硬编码菜单 ID，避免错位）。
--     历史上这里踩过三次坑，写法已定型为「INSERT...SELECT + JOIN 派生表」，勿改回子查询：
--       ① Duplicate entry '0-system:reset' —— 重复插入 V36 已有权限点（已剔除）；
--       ② MySQL 1093 —— 在 VALUES 里嵌 (SELECT id FROM sys_menu ...) 自表子查询，MySQL 禁止；
--       ③ Error 1048 parent_id cannot be null —— 改用派生表包一层后，某父 component 在
--          库中查不到 → 子查询返回 NULL → 撞 NOT NULL 约束。
--     现方案 JOIN 派生表 + MIN(id) 聚合：父菜单存在才插入，不存在则静默跳过该按钮节点，
--     永不产生 NULL；且每个 component 只匹配一行，不会因重复父菜单放大行数。
--   - agreement:publish 仅建权限点 + 授权：协议管理页 agreement-manage.vue 当前
--     未挂菜单（孤儿页），无父菜单可挂，故不建 BUTTON 节点。
-- ============================================================================

-- 1. 权限点（sys_permission.id 自增，不显式指定）
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
-- 立项
('project:create',  '立项创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('project:edit',    '立项编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('project:sign',    '立项签约',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 发货
('shipment:create', '发货创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('shipment:edit',   '发货编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('shipment:receive','发货确认收货', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
('shipment:upload', '发货上传签收', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 发票
('invoice:create',  '发票创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('invoice:edit',    '发票编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('invoice:upload',  '发票上传',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('invoice:download','发票下载',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 回款
('receipt:create',  '回款创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('receipt:edit',    '回款编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('receipt:upload',  '回款上传回单', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
('receipt:download','回款下载回单', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 退货
('return:create',   '退货创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('return:cancel',   '退货撤销',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('return:approve',  '退货通过',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('return:reject',   '退货驳回',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 核销
('writeoff:writeoff','核销自动核销', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
('writeoff:reverse', '核销反核销',  'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 流程
('process:create',  '流程创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('process:edit',    '流程编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('process:config',  '流程配置节点', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
('process:deploy',  '流程部署',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('process:toggle',  '流程启停',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 字典
('dict:create',     '字典类型创建', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
('dict:edit',       '字典类型编辑', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
('dict:delete',     '字典类型删除', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
('dict:data:create','字典数据创建', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
('dict:data:edit',  '字典数据编辑', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
('dict:data:delete','字典数据删除', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 职级
('level:create',    '职级创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('level:edit',      '职级编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('level:delete',    '职级删除',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 部门
('org:create',      '部门创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('org:edit',        '部门编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('org:delete',      '部门删除',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 岗位
('post:create',     '岗位创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('post:edit',       '岗位编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('post:delete',     '岗位删除',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 用户
('user:create',     '用户创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('user:edit',       '用户编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('user:delete',     '用户删除',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 角色
('role:create',     '角色创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('role:edit',       '角色编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('role:delete',     '角色删除',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('role:assign',     '角色分配权限', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 菜单
('menu:create',     '菜单创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('menu:edit',       '菜单编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('menu:delete',     '菜单删除',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 权限
('permission:create','权限创建',    'BUTTON', 'EXECUTE', 'ENABLED', 0),
('permission:edit', '权限编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('permission:delete','权限删除',    'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 反馈
('feedback:create', '反馈创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 帮助
('help:create',     '帮助创建',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('help:edit',       '帮助编辑',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('help:delete',     '帮助删除',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('help:toggle',     '帮助启停',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 通知
('notify:publish',      '通知发布',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('notify:template:edit','通知模板编辑', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 参数设置
('config:create',   '参数键值创建', 'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 系统重置：权限点 system:reset 已由 V36__init_reset_menu.sql 建立（sys_permission 有
--   uk_tenant_perm_code 唯一键，重复 INSERT 会撞 Duplicate entry 并导致本迁移整体失败）。
--   此处不重复插入，其余按钮权限点保持不变。
-- 文件
('file:upload',     '文件上传',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('file:download',   '文件下载',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('file:alias',      '文件别名',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('file:delete',     '文件删除',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 导出（任务2：业务数据页导出 xlsx）
('project:export',    '立项导出',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('shipment:export',   '发货导出',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('invoice:export',    '发票导出',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('receipt:export',    '回款导出',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('receivable:export', '应收导出',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('return:export',     '退货导出',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
('writeoff:export',   '核销导出',     'BUTTON', 'EXECUTE', 'ENABLED', 0),
-- 协议（仅权限点 + 授权，无菜单节点）
('agreement:publish','协议发布',    'BUTTON', 'EXECUTE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `status` = VALUES(`status`);

-- 2. 菜单 BUTTON 节点（挂到对应页面菜单下；防御式：父菜单不存在则自动跳过该节点）
--    id 用 301+ 段（现有最大菜单 id=116，无冲突）；route_path/component 为空（按钮无独立页面）
--    注意：若某父菜单在库中缺失，对应按钮会静默跳过 —— 权限点仍在 sys_permission 中，
--          后端 @RequirePerm 与前端 v-has-perm 均正常，仅菜单树里看不见。
--          缺失清单可用本注释下方 SQL 查询后用单条 INSERT 补回。
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT d.id, d.menu_name, pm.id, '', NULL, d.perm_code, 'BUTTON', d.sort_no, 1, 'ENABLED', 0
FROM (
  SELECT 301 AS id, '立项创建' AS menu_name, 'project/list' AS parent_component, 'project:create' AS perm_code, 1 AS sort_no
  UNION ALL SELECT 302, '立项编辑', 'project/list', 'project:edit', 2
  UNION ALL SELECT 303, '立项签约', 'project/list', 'project:sign', 3
  UNION ALL SELECT 304, '发货创建', 'shipment/list', 'shipment:create', 1
  UNION ALL SELECT 305, '发货编辑', 'shipment/list', 'shipment:edit', 2
  UNION ALL SELECT 306, '发货确认收货', 'shipment/list', 'shipment:receive', 3
  UNION ALL SELECT 307, '发货上传签收', 'shipment/list', 'shipment:upload', 4
  UNION ALL SELECT 308, '发票创建', 'invoice/list', 'invoice:create', 1
  UNION ALL SELECT 309, '发票编辑', 'invoice/list', 'invoice:edit', 2
  UNION ALL SELECT 310, '发票上传', 'invoice/list', 'invoice:upload', 3
  UNION ALL SELECT 311, '发票下载', 'invoice/list', 'invoice:download', 4
  UNION ALL SELECT 312, '回款创建', 'receipt/list', 'receipt:create', 1
  UNION ALL SELECT 313, '回款编辑', 'receipt/list', 'receipt:edit', 2
  UNION ALL SELECT 314, '回款上传回单', 'receipt/list', 'receipt:upload', 3
  UNION ALL SELECT 315, '回款下载回单', 'receipt/list', 'receipt:download', 4
  UNION ALL SELECT 316, '退货创建', 'return/list', 'return:create', 1
  UNION ALL SELECT 317, '退货撤销', 'return/list', 'return:cancel', 2
  UNION ALL SELECT 318, '退货通过', 'return/list', 'return:approve', 3
  UNION ALL SELECT 319, '退货驳回', 'return/list', 'return:reject', 4
  UNION ALL SELECT 320, '核销自动核销', 'writeoff/list', 'writeoff:writeoff', 1
  UNION ALL SELECT 321, '核销反核销', 'writeoff/list', 'writeoff:reverse', 2
  UNION ALL SELECT 322, '流程创建', 'process/list', 'process:create', 1
  UNION ALL SELECT 323, '流程编辑', 'process/list', 'process:edit', 2
  UNION ALL SELECT 324, '流程配置节点', 'process/list', 'process:config', 3
  UNION ALL SELECT 325, '流程部署', 'process/list', 'process:deploy', 4
  UNION ALL SELECT 326, '流程启停', 'process/list', 'process:toggle', 5
  UNION ALL SELECT 327, '字典类型创建', 'system/dict', 'dict:create', 1
  UNION ALL SELECT 328, '字典类型编辑', 'system/dict', 'dict:edit', 2
  UNION ALL SELECT 329, '字典类型删除', 'system/dict', 'dict:delete', 3
  UNION ALL SELECT 330, '字典数据创建', 'system/dict-data', 'dict:data:create', 1
  UNION ALL SELECT 331, '字典数据编辑', 'system/dict-data', 'dict:data:edit', 2
  UNION ALL SELECT 332, '字典数据删除', 'system/dict-data', 'dict:data:delete', 3
  UNION ALL SELECT 333, '职级创建', 'system/level', 'level:create', 1
  UNION ALL SELECT 334, '职级编辑', 'system/level', 'level:edit', 2
  UNION ALL SELECT 335, '职级删除', 'system/level', 'level:delete', 3
  UNION ALL SELECT 336, '部门创建', 'system/org', 'org:create', 1
  UNION ALL SELECT 337, '部门编辑', 'system/org', 'org:edit', 2
  UNION ALL SELECT 338, '部门删除', 'system/org', 'org:delete', 3
  UNION ALL SELECT 339, '岗位创建', 'system/post', 'post:create', 1
  UNION ALL SELECT 340, '岗位编辑', 'system/post', 'post:edit', 2
  UNION ALL SELECT 341, '岗位删除', 'system/post', 'post:delete', 3
  UNION ALL SELECT 342, '用户创建', 'system/user', 'user:create', 1
  UNION ALL SELECT 343, '用户编辑', 'system/user', 'user:edit', 2
  UNION ALL SELECT 344, '用户删除', 'system/user', 'user:delete', 3
  UNION ALL SELECT 345, '角色创建', 'system/role', 'role:create', 1
  UNION ALL SELECT 346, '角色编辑', 'system/role', 'role:edit', 2
  UNION ALL SELECT 347, '角色删除', 'system/role', 'role:delete', 3
  UNION ALL SELECT 348, '角色分配权限', 'system/role', 'role:assign', 4
  UNION ALL SELECT 349, '菜单创建', 'system/menu', 'menu:create', 1
  UNION ALL SELECT 350, '菜单编辑', 'system/menu', 'menu:edit', 2
  UNION ALL SELECT 351, '菜单删除', 'system/menu', 'menu:delete', 3
  UNION ALL SELECT 352, '权限创建', 'system/permission', 'permission:create', 1
  UNION ALL SELECT 353, '权限编辑', 'system/permission', 'permission:edit', 2
  UNION ALL SELECT 354, '权限删除', 'system/permission', 'permission:delete', 3
  UNION ALL SELECT 355, '反馈创建', 'system/feedback', 'feedback:create', 1
  UNION ALL SELECT 356, '帮助创建', 'system/help-manage', 'help:create', 1
  UNION ALL SELECT 357, '帮助编辑', 'system/help-manage', 'help:edit', 2
  UNION ALL SELECT 358, '帮助删除', 'system/help-manage', 'help:delete', 3
  UNION ALL SELECT 359, '帮助启停', 'system/help-manage', 'help:toggle', 4
  UNION ALL SELECT 360, '通知发布', 'system/notify-record', 'notify:publish', 1
  UNION ALL SELECT 361, '通知模板编辑', 'system/notify-template', 'notify:template:edit', 1
  UNION ALL SELECT 362, '参数键值创建', 'system/param-setting', 'config:create', 1
  UNION ALL SELECT 364, '文件上传', 'system/file', 'file:upload', 1
  UNION ALL SELECT 365, '文件下载', 'system/file', 'file:download', 2
  UNION ALL SELECT 366, '文件别名', 'system/file', 'file:alias', 3
  UNION ALL SELECT 367, '文件删除', 'system/file', 'file:delete', 4
  UNION ALL SELECT 368, '立项导出', 'project/list', 'project:export', 99
  UNION ALL SELECT 369, '发货导出', 'shipment/list', 'shipment:export', 99
  UNION ALL SELECT 370, '发票导出', 'invoice/list', 'invoice:export', 99
  UNION ALL SELECT 371, '回款导出', 'receipt/list', 'receipt:export', 99
  UNION ALL SELECT 372, '应收导出', 'receivable/list', 'receivable:export', 99
  UNION ALL SELECT 373, '退货导出', 'return/list', 'return:export', 99
  UNION ALL SELECT 374, '核销导出', 'writeoff/list', 'writeoff:export', 99
) d
JOIN (SELECT `component`, MIN(`id`) AS id FROM `sys_menu` WHERE `menu_type` = 'MENU' GROUP BY `component`) pm
  ON pm.`component` = d.parent_component
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`);

-- 3. 默认授权 ROLE_ADMIN（默认打开：存量 admin 自动拥有全部新按钮权限点）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN'
  AND p.perm_code IN (
    'project:create','project:edit','project:sign',
    'shipment:create','shipment:edit','shipment:receive','shipment:upload',
    'invoice:create','invoice:edit','invoice:upload','invoice:download',
    'receipt:create','receipt:edit','receipt:upload','receipt:download',
    'return:create','return:cancel','return:approve','return:reject',
    'writeoff:writeoff','writeoff:reverse',
    'process:create','process:edit','process:config','process:deploy','process:toggle',
    'dict:create','dict:edit','dict:delete',
    'dict:data:create','dict:data:edit','dict:data:delete',
    'level:create','level:edit','level:delete',
    'org:create','org:edit','org:delete',
    'post:create','post:edit','post:delete',
    'user:create','user:edit','user:delete',
    'role:create','role:edit','role:delete','role:assign',
    'menu:create','menu:edit','menu:delete',
    'permission:create','permission:edit','permission:delete',
    'feedback:create',
    'help:create','help:edit','help:delete','help:toggle',
    'notify:publish','notify:template:edit',
    'config:create',
    'file:upload','file:download','file:alias','file:delete',
    'project:export','shipment:export','invoice:export','receipt:export','receivable:export','return:export','writeoff:export',
    'agreement:publish'
  )
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

-- 4. 默认打开（兼容非 admin 角色）：把每个动作码授权给「已持有同模块任意权限」的角色，
--    避免后续后端把 @RequirePerm("xxx:manage") 细化为动作码时，旧角色凭空丢失按钮/接口。
--    规则：动作码前缀（module:）与已持有 perm_code 前缀一致即授予；ON DUPLICATE 防重复。
--    注：源表 sys_role_permission 用派生表包裹，规避 MySQL 1093
--        （INSERT 的目标表不能直接出现在子查询 FROM 里，见语句 2 的踩坑说明）。
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT DISTINCT x.role_id, p.id, 'ENABLED', 0
FROM (
    -- 先物化成派生表：role_id + 该角色「已持有的权限码」
    SELECT DISTINCT rp.role_id, pm.perm_code AS held_perm_code
    FROM `sys_role_permission` rp
    JOIN `sys_permission` pm ON pm.id = rp.permission_id
) x
JOIN `sys_permission` p
  ON p.perm_code IN (
    'project:create','project:edit','project:sign',
    'shipment:create','shipment:edit','shipment:receive','shipment:upload',
    'invoice:create','invoice:edit','invoice:upload','invoice:download',
    'receipt:create','receipt:edit','receipt:upload','receipt:download',
    'return:create','return:cancel','return:approve','return:reject',
    'writeoff:writeoff','writeoff:reverse',
    'process:create','process:edit','process:config','process:deploy','process:toggle',
    'dict:create','dict:edit','dict:delete',
    'dict:data:create','dict:data:edit','dict:data:delete',
    'level:create','level:edit','level:delete',
    'org:create','org:edit','org:delete',
    'post:create','post:edit','post:delete',
    'user:create','user:edit','user:delete',
    'role:create','role:edit','role:delete','role:assign',
    'menu:create','menu:edit','menu:delete',
    'permission:create','permission:edit','permission:delete',
    'feedback:create',
    'help:create','help:edit','help:delete','help:toggle',
    'notify:publish','notify:template:edit',
    'config:create',
    'file:upload','file:download','file:alias','file:delete',
    'project:export','shipment:export','invoice:export','receipt:export','receivable:export','return:export','writeoff:export',
    'agreement:publish'
  )
WHERE SUBSTRING_INDEX(p.perm_code, ':', 1) = SUBSTRING_INDEX(x.held_perm_code, ':', 1)
  AND x.held_perm_code <> p.perm_code
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';
