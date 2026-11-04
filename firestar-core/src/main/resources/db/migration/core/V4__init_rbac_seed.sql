-- ============================================================================
-- V3：RBAC 种子数据（认证模块收口依赖）
-- 顺序：V1(baseline) → V2(dict + role) → V3(this) → V4(token_blacklist)
-- 内容：admin 用户 + 角色绑定 + 权限表 + 菜单树 + 角色权限/菜单关联
-- 说明：密码 admin123 的 BCrypt 哈希（首次登录强制改密）；
--       权限采用模块级粗粒度（view / manage），后续业务模块实现时按 perm_code 做 @PreAuthorize。
-- ============================================================================

-- 1. 系统管理员账号（密码 admin123 的 BCrypt 哈希，首次登录强制改密）
INSERT INTO `sys_user` (`id`, `username`, `nickname`, `password`, `force_change_password`, `status`, `tenant_id`)
VALUES (1, 'admin', '系统管理员', '$2a$10$yfUXVdlPc4gjXlN1TL/7A.VgLa4EjZrPd/62/TrYZbbuLppbZZ7gO', 1, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `password` = VALUES(`password`), `status` = VALUES(`status`);

-- 2. 管理员绑定 ROLE_ADMIN
INSERT INTO `sys_user_role` (`user_id`, `role_id`, `tenant_id`)
SELECT 1, r.id, 0 FROM `sys_role` r WHERE r.role_code = 'ROLE_ADMIN'
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);

-- 3. 权限表（模块级粗粒度：view / manage；认证模块按接口拆分）
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('auth:login',      '登录',         'API', 'EXECUTE', 'ENABLED', 0),
('auth:logout',     '退出登录',     'API', 'EXECUTE', 'ENABLED', 0),
('auth:profile',    '获取个人信息', 'API', 'READ',    'ENABLED', 0),
('auth:password',   '修改密码',     'API', 'WRITE',   'ENABLED', 0),
('auth:menus',      '获取菜单',     'API', 'READ',    'ENABLED', 0),
('project:view',    '项目查看',     'API', 'READ',    'ENABLED', 0),
('project:manage',  '项目管理',     'API', 'WRITE',   'ENABLED', 0),
('item:view',       '货物明细查看', 'API', 'READ',    'ENABLED', 0),
('item:manage',     '货物明细管理', 'API', 'WRITE',   'ENABLED', 0),
('shipment:view',   '发货查看',     'API', 'READ',    'ENABLED', 0),
('shipment:manage', '发货管理',     'API', 'WRITE',   'ENABLED', 0),
('receivable:view', '应收查看',     'API', 'READ',    'ENABLED', 0),
('receivable:manage','应收管理',    'API', 'WRITE',   'ENABLED', 0),
('receipt:view',    '收款查看',     'API', 'READ',    'ENABLED', 0),
('receipt:manage',  '收款管理',     'API', 'WRITE',   'ENABLED', 0),
('invoice:view',    '发票查看',     'API', 'READ',    'ENABLED', 0),
('invoice:manage',  '发票管理',     'API', 'WRITE',   'ENABLED', 0),
('writeoff:view',   '核销查看',     'API', 'READ',    'ENABLED', 0),
('writeoff:manage', '核销管理',     'API', 'WRITE',   'ENABLED', 0),
('return:view',     '退货查看',     'API', 'READ',    'ENABLED', 0),
('return:manage',   '退货管理',     'API', 'WRITE',   'ENABLED', 0),
('approval:view',   '审批查看',     'API', 'READ',    'ENABLED', 0),
('approval:manage', '审批管理',     'API', 'WRITE',   'ENABLED', 0),
('dashboard:view',  '数据看板',     'API', 'READ',    'ENABLED', 0),
('file:view',       '文件查看',     'API', 'READ',    'ENABLED', 0),
('file:manage',     '文件管理',     'API', 'WRITE',   'ENABLED', 0),
('dict:view',       '字典查看',     'API', 'READ',    'ENABLED', 0),
('dict:manage',     '字典管理',     'API', 'WRITE',   'ENABLED', 0),
('rbac:user',      '用户管理',      'API', 'WRITE',   'ENABLED', 0),
('rbac:role',      '角色管理',      'API', 'WRITE',   'ENABLED', 0),
('rbac:perm',      '权限管理',      'API', 'WRITE',   'ENABLED', 0),
('rbac:menu',      '菜单管理',      'API', 'WRITE',   'ENABLED', 0),
('system:db-check', '运维自检',     'API', 'EXECUTE', 'ENABLED', 0);

-- 4. 菜单树（对应 15 业务模块 + 工作台 / 系统管理；route_path / component 为占位，
--    前端后续按此路由建页，menus 接口先返回结构）
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`) VALUES
(1,  '工作台',   0,  '/dashboard',    'dashboard/index',  'dashboard:view', 'MENU',    1, 1, 'ENABLED', 0),
(2,  '项目管理', 0,  '',              '',                  NULL,            'CATALOG', 2, 1, 'ENABLED', 0),
(3,  '项目列表', 2,  '/project',      'project/list',      'project:view',  'MENU',    1, 1, 'ENABLED', 0),
(4,  '发货管理', 0,  '/shipment',     'shipment/list',     'shipment:view', 'MENU',    3, 1, 'ENABLED', 0),
(5,  '应收管理', 0,  '/receivable',   'receivable/list',   'receivable:view','MENU',    4, 1, 'ENABLED', 0),
(6,  '收款管理', 0,  '/receipt',      'receipt/list',      'receipt:view',  'MENU',    5, 1, 'ENABLED', 0),
(7,  '发票管理', 0,  '/invoice',      'invoice/list',      'invoice:view',  'MENU',    6, 1, 'ENABLED', 0),
(8,  '核销管理', 0,  '/writeoff',     'writeoff/list',     'writeoff:view', 'MENU',    7, 1, 'ENABLED', 0),
(9,  '退货管理', 0,  '/return',       'return/list',       'return:view',   'MENU',    8, 1, 'ENABLED', 0),
(10, '审批中心', 0,  '/approval',     'approval/list',     'approval:view', 'MENU',    9, 1, 'ENABLED', 0),
(11, '数据看板', 0,  '/dashboard/data','dashboard/data',   'dashboard:view','MENU',   10, 1, 'ENABLED', 0),
(12, '系统管理', 0,  '',              '',                  NULL,            'CATALOG', 11, 1, 'ENABLED', 0),
(13, '用户管理', 12, '/system/user',  'system/user',       'rbac:user',     'MENU',    1, 1, 'ENABLED', 0),
(14, '角色管理', 12, '/system/role',  'system/role',       'rbac:role',     'MENU',    2, 1, 'ENABLED', 0),
(15, '权限管理', 12, '/system/permission','system/permission','rbac:perm',  'MENU',    3, 1, 'ENABLED', 0),
(16, '菜单管理', 12, '/system/menu',  'system/menu',       'rbac:menu',     'MENU',    4, 1, 'ENABLED', 0),
(17, '字典管理', 12, '/system/dict',  'system/dict',       'dict:manage',   'MENU',    5, 1, 'ENABLED', 0),
(18, '文件管理', 12, '/system/file',  'system/file',       'file:manage',   'MENU',    6, 1, 'ENABLED', 0);

-- 5. ROLE_ADMIN 拥有全部权限
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN';

-- 6. ROLE_ADMIN 拥有全部菜单
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN';
