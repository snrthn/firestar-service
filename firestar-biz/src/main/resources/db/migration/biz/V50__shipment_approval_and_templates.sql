-- ============================================================================
-- V50：发货审批流程定义 + 通知模板 + 发货状态「已驳回」
-- 依赖：V1（sys_process_def / sys_notification_template 表已建）、V2（shipment_status 字典类型已建）
-- 幂等：process_def 用 uk_tenant_process_code 防重；模板用 uk_tpl_code 防重；字典用 (type_code, dict_value) 唯一约束防重。
--
-- 背景：发货申请走一步 ROLE 审批（决策#1，虎哥 2026-10-23），审批人 = ROLE_SALES_MANAGER（决策#2）。
-- 审批通过 → 通知审批人（=发货人）引导发货；驳回 → 终结流程 + 置 REJECTED + 通知申请人（决策#2/#6 同人逻辑）。
--
-- 部署说明：flowable_deployment_id 置 NULL，由 ProcessDefAutoDeployRunner（启动自动部署器）
-- 在应用启动时把 ENABLED 且未部署的流程定义部署进 Flowable，免去手动调 deploy 端点。
-- （project_initiate 当前同为 NULL，启动时会一并被自动部署，修复「立项流程从未部署」的历史缺口，
--   也借此真实验证 P3 配置化引擎的通用性——首处脱离 project_initiate 的第二套流程。）
-- ============================================================================

-- ========== 1. 发货审批流程定义（一步 ROLE 节点） ==========
INSERT INTO `sys_process_def`
    (process_code, process_name, flowable_deployment_id, def_version, status, node_list, remark, tenant_id)
VALUES
    ('shipment_approval', '发货审批', NULL, 1, 'ENABLED',
     '[
       {"seq":1,"nodeId":"node_1","name":"发货审批","type":"ROLE","assignee":"ROLE_SALES_MANAGER","rejectable":true}
     ]',
     'V50 种子：发货一步 ROLE 审批（ROLE_SALES_MANAGER）。审批通过→通知审批人引导发货；驳回→终结流程+REJECTED。',
     0)
ON DUPLICATE KEY UPDATE
    process_name = VALUES(process_name),
    status      = VALUES(status),
    node_list   = VALUES(node_list),
    remark      = VALUES(remark);

-- ========== 2. 通知模板 3 条（发货提交/通过引导/驳回） ==========
-- 变量对齐 ShipmentServiceImpl 的 notifySubmitted / notifyApproved / notifyRejected 的 vars：
--   SHIPMENT_SUBMITTED     {{projectName}}
--   SHIPMENT_APPROVED_GUIDE {{batchNo}} {{projectName}}
--   SHIPMENT_REJECTED      {{batchNo}} {{projectName}} {{approver}} {{comment}}
INSERT INTO `sys_notification_template` (`code`, `type`, `title_tpl`, `content_tpl`, `enabled`, `tenant_id`) VALUES
('SHIPMENT_SUBMITTED',     'SHIPMENT_SUBMITTED',     '发货审批待处理：{{projectName}}',            '有新的发货申请（{{projectName}}）等待您审批，请及时处理。', 1, 0),
('SHIPMENT_APPROVED_GUIDE','SHIPMENT_APPROVED_GUIDE','发货申请已通过：{{batchNo}}',                '您提交的发货单 {{batchNo}}（{{projectName}}）已审批通过，请前往发货。', 1, 0),
('SHIPMENT_REJECTED',      'SHIPMENT_REJECTED',      '发货申请被退回：{{batchNo}}',                '您提交的发货单 {{batchNo}}（{{projectName}}）被 {{approver}} 退回：{{comment}}', 1, 0)
ON DUPLICATE KEY UPDATE `type` = VALUES(`type`), `title_tpl` = VALUES(`title_tpl`), `content_tpl` = VALUES(`content_tpl`), `enabled` = VALUES(`enabled`);

-- ========== 3. shipment_status 补 REJECTED（单节点审批驳回后回到申请人，决策#2/#6） ==========
-- 与 V2 既有 5 个值同序：PENDING(1) / PENDING_CONFIRM(2) / SHIPPED(3) / PARTIAL_RECEIVED(4) / FULL_RECEIVED(5) → REJECTED(6)
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('shipment_status', '已驳回', 'REJECTED', 6, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);
