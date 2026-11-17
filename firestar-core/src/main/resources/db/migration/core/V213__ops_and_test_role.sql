-- ============================================================================
-- V213：新增「运维管理员」「测试账号」两个角色 + 授权（虎哥 2026-11-17 拍板）
--
-- 背景：
--   1. P0-1 真机验收暴露——所有 AI 权限（ai:assistant:use / ai:kb:* / ai:prompt:*
--      / sys:modelConfig:*）只授予 ROLE_ADMIN，仓库仅 admin 一个账号，越权验证
--      只能临时造号再清理。补两个固定角色，供运维分权 + 测试身份长期复用。
--   2. 本次只建角色 + 授权，不建 sys_user 账号（虎哥拍板：账号自己在 RBAC 界面建）。
--
-- 角色权限范围（虎哥二选一拍板：运维=运维自检+AI，测试=只读）：
--   · ROLE_OPS_ADMIN 运维管理员：运维自检(system:db-check) + 字典/文件/用户管理
--     (RBAC 四件套) + AI 全套（助手/模型配置/提示词/知识库）。data_scope=ALL。
--   · ROLE_TEST 测试账号：只读——工作台(dashboard:view) + 知识库查看(ai:kb:view)。
--     无 ai:assistant:use、无 kb 编辑/发布、无任何其他业务权限。data_scope=SELF。
--
-- ⚠️ 同步项（漏改会 /actuator/health DOWN → 503，V19/V20/V22 同款事故）：
--    sys_role 种子数 7 → 9，DatabaseCheckService.EXPECTED_ROLE_COUNT 必须同步改 9L。
--    本迁移不新增表 / 字典 / 权限点 / 菜单（全部复用既有行），其余计数常量不变。
--
-- 幂等：sys_role(uk_tenant_role_code)、sys_role_permission(role_id,permission_id)、
--       sys_role_menu(role_id,menu_id) 均有唯一键，全部 ON DUPLICATE KEY UPDATE，重复执行安全。
-- 依赖：V1(baseline, sys_role/permission/menu) → V2(7 基础角色) → V4(权限点/菜单/系统管理员)
--       → V101/V102(模型配置) → V103(AI中心/助手) → V106/V107(提示词) → V207(知识库)。
-- ============================================================================

-- ========== 1. 角色本体（sort_no 接 V2 的 1~7，取 8/9；status 用新约定 ENABLED） ==========
INSERT INTO `sys_role` (`role_code`, `role_name`, `data_scope`, `sort_no`, `status`, `remark`, `tenant_id`)
VALUES
('ROLE_OPS_ADMIN', '运维管理员', 'ALL',  8, 'ENABLED', '运维自检 + 字典/文件/用户管理 + AI 全套配置权，不碰财务业务审批', 0),
('ROLE_TEST',      '测试账号',   'SELF', 9, 'ENABLED', '只读身份：工作台 + 知识库查看，用于越权拦截与检索验收', 0)
ON DUPLICATE KEY UPDATE
    `role_name` = VALUES(`role_name`),
    `data_scope`= VALUES(`data_scope`),
    `sort_no`   = VALUES(`sort_no`),
    `status`    = VALUES(`status`),
    `remark`    = VALUES(`remark`);

-- ========== 2.1 ROLE_OPS_ADMIN 操作权限（接口 @RequirePerm 放行线） ==========
-- 全部为既有权限点，本迁移不新增 sys_permission 行。
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_OPS_ADMIN'
  AND p.perm_code IN (
    'system:db-check',        -- 运维自检（V4 权限点；接口无强制校验，作前端卡片显隐 + 角色标识）
    'dict:view', 'dict:manage',       -- 字典管理
    'file:view', 'file:manage',       -- 文件管理
    'rbac:user', 'rbac:role', 'rbac:perm', 'rbac:menu',  -- 用户/角色/权限/菜单管理
    'sys:modelConfig:view', 'sys:modelConfig:edit',      -- AI 模型配置
    'ai:assistant:use',                                  -- AI 助手入口
    'ai:prompt:view', 'ai:prompt:edit', 'ai:prompt:use', -- AI 提示词
    'ai:kb:view', 'ai:kb:edit', 'ai:kb:publish'          -- 知识库（含检索测试端点 ai:kb:view）
  )
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

-- ========== 2.2 ROLE_OPS_ADMIN 菜单（侧栏显隐 + 按钮权限集） ==========
-- 系统管理：用户13/角色14/权限15/菜单16/字典17/文件18 + 工作台1
-- AI中心：95(CATALOG) + 94模型配置 + 9401~9404按钮 + 9501助手入口
--        + 9502提示词 + 95021~95024按钮 + 9503知识库 + 95031~95034按钮
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_OPS_ADMIN'
  AND m.id IN (
    1, 13, 14, 15, 16, 17, 18,
    94, 95, 9401, 9402, 9403, 9404, 9501,
    9502, 95021, 95022, 95023, 95024,
    9503, 95031, 95032, 95033, 95034
  )
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);

-- ========== 3.1 ROLE_TEST 操作权限：只读 ==========
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_TEST'
  AND p.perm_code IN ('dashboard:view', 'ai:kb:view')
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

-- ========== 3.2 ROLE_TEST 菜单：工作台(1) + 知识库(9503) ==========
-- 只授 MENU 不授 BUTTON：知识库页可见可读，但新增/编辑/发布按钮(v-has-perm)全隐 → 只读。
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_TEST' AND m.id IN (1, 9503)
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
