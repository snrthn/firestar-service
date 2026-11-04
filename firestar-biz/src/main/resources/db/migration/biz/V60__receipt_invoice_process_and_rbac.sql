-- ============================================================================
-- V60：收款/发票 审批流程定义 + 出纳/开票员 RBAC 授权（2026-10-24）
-- 依赖：V1（sys_process_def / sys_role / sys_permission / sys_menu / sys_role_permission / sys_role_menu）
--       V4（权限点 receipt:*/invoice:* 与菜单 收款管理(6)/发票管理(7)/应收管理(5)/审批中心(10) 已种）
--       V2（角色 ROLE_CASHIER / ROLE_INVOICER 已建）
-- 背景：收款/发票走审批流（虎哥 2026-10-24 拍板）：申请人建单提交 →
--       ROLE_CASHIER（收款）/ ROLE_INVOICER（发票）单节点审批 → 终态。
--       ROLE_CASHIER / ROLE_INVOICER 自 V2 建好但从未授权 → 进系统看不见菜单/接口，本迁移补齐。
-- 部署说明：flowable_deployment_id 置 NULL，由 ProcessDefAutoDeployRunner 启动自动部署。
-- 幂等：process_def 用 uk_tenant_process_code 防重；role_permission / role_menu 用 ON DUPLICATE KEY。
-- ============================================================================

-- 1. 收款审批流程（一步 ROLE 节点：ROLE_CASHIER）
INSERT INTO `sys_process_def`
    (process_code, process_name, flowable_deployment_id, def_version, status, node_list, remark, tenant_id)
VALUES
    ('receipt_approval', '收款审批', NULL, 1, 'ENABLED',
     '[
       {"seq":1,"nodeId":"node_1","name":"收款审批","type":"ROLE","assignee":"ROLE_CASHIER","rejectable":true}
     ]',
     'V60 种子：收款一步 ROLE 审批（ROLE_CASHIER）。审批通过→回写已收款+累加 fin_project.collected_amount。',
     0)
ON DUPLICATE KEY UPDATE
    process_name = VALUES(process_name),
    status      = VALUES(status),
    node_list   = VALUES(node_list),
    remark      = VALUES(remark);

-- 2. 发票审批流程（一步 ROLE 节点：ROLE_INVOICER）
INSERT INTO `sys_process_def`
    (process_code, process_name, flowable_deployment_id, def_version, status, node_list, remark, tenant_id)
VALUES
    ('invoice_approval', '发票审批', NULL, 1, 'ENABLED',
     '[
       {"seq":1,"nodeId":"node_1","name":"发票审批","type":"ROLE","assignee":"ROLE_INVOICER","rejectable":true}
     ]',
     'V60 种子：发票一步 ROLE 审批（ROLE_INVOICER）。审批通过→回写已开票+累加 fin_project.invoiced_amount。',
     0)
ON DUPLICATE KEY UPDATE
    process_name = VALUES(process_name),
    status      = VALUES(status),
    node_list   = VALUES(node_list),
    remark      = VALUES(remark);

-- 3. 出纳 ROLE_CASHIER 授权：权限 + 菜单（菜单 6 收款 / 5 应收 / 10 审批中心）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_CASHIER'
  AND p.perm_code IN (
    'receipt:view', 'receipt:manage',
    'approval:view', 'approval:manage',
    'receivable:view',
    'file:view'
  )
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_CASHIER'
  AND m.id IN (6, 5, 10)
ON DUPLICATE KEY UPDATE `menu_id` = VALUES(`menu_id`);

-- 4. 开票员 ROLE_INVOICER 授权：权限 + 菜单（菜单 7 发票 / 5 应收 / 10 审批中心）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_INVOICER'
  AND p.perm_code IN (
    'invoice:view', 'invoice:manage',
    'approval:view', 'approval:manage',
    'receivable:view',
    'file:view'
  )
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_INVOICER'
  AND m.id IN (7, 5, 10)
ON DUPLICATE KEY UPDATE `menu_id` = VALUES(`menu_id`);
