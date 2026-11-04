-- ============================================================================
-- V53: ROLE_SALES_MANAGER 销售经理 RBAC 授权种子（2026-10-23 虎哥拍板）
-- 背景：V4 种子只给 ROLE_ADMIN 授权；销售经理角色自 V2 建好但从未授权，导致：
--   ① 发货/立项审批链只有 admin 能跑（非 admin 账号 403）；
--   ② 审批中心、发货菜单对销售经理不可见。
-- 本迁移：角色 → 权限 + 菜单授权（用户↔角色绑定属运营数据，虎哥已在 RBAC 界面创建，不种）。
-- 幂等：ON DUPLICATE KEY UPDATE，重复执行安全，供生产同步。
-- ============================================================================

-- 1. 权限授权：销售业务域（立项/发货全流程）+ 审批中心 + 应收只读 + 文件（收货证据上传/查看）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_SALES_MANAGER'
  AND p.perm_code IN (
    'project:view',       -- 立项查看
    'project:manage',     -- 立项创建/编辑/提交
    'shipment:view',      -- 发货查看
    'shipment:manage',    -- 发货申请/标记发货/客户收货
    'approval:view',      -- 审批中心查看
    'approval:manage',    -- 审批中心处理（通过/驳回）
    'receivable:view',    -- 应收只读（不开票不核销，属财务域）
    'file:view'           -- 文件上传/查看（收货证据留痕，FileController 类级权限）
  )
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

-- 2. 菜单授权：项目列表(3) / 发货管理(4) / 应收管理(5) / 审批中心(10)（id 同 V4 种子）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_SALES_MANAGER'
  AND m.id IN (3, 4, 5, 10)
ON DUPLICATE KEY UPDATE `menu_id` = VALUES(`menu_id`);
