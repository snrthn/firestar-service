-- ============================================================================
-- V67：退货全栈地基（2026-10-25 阶段二）
-- 依赖：V1（fin_return_order / sys_process_def / sys_notification_template 已建）
--       V2（return_approval_status / return_execute_status / receipt_type /
--           receivable_source / doc_direction 字典已种）
--       V4（return:view / return:manage 权限点已种）、V1 菜单 9「退货管理」已注册
-- 背景：虎哥拍板「建 fin_return_line 按行退 + 红字收款单做退款单 + auto 核销」。
--       退货单本体 V1 早有（fin_return_order + 字典 + 菜单 + 权限全是空壳待代码），
--       本迁移补齐：① 明细行表 ② 三级审批流程定义 ③ 退款/退货来源字典值
--       ④ 撤销状态字典 ⑤ 四个通知模板。
-- 幂等：全部 INSERT ... ON DUPLICATE KEY UPDATE，可重复执行。
-- ============================================================================

-- ========== 1. fin_return_line 退货明细行（严格同构 V1 fin_shipment_line） ==========
-- 虎哥决策#1：按行退——退哪些货、每行退几件、行单价多少；
-- 退货金额 = Σ(行数量 × 行单价)（ReturnServiceImpl 汇总，不冗余存单头）。
CREATE TABLE IF NOT EXISTS `fin_return_line` (
  `id`             bigint       NOT NULL AUTO_INCREMENT,
  `return_id`      bigint       NOT NULL COMMENT '退货单 ID → fin_return_order.id',
  `shipment_line_id` bigint     NOT NULL COMMENT '发货明细行 ID → fin_shipment_line.id',
  `item_id`        bigint       NOT NULL COMMENT '货物 ID → fin_item.id',
  `return_qty`     int          NOT NULL DEFAULT 0 COMMENT '退货数量',
  `unit_price`     decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '单价（冗余发货行单价，防改价追溯歧义）',
  `line_amount`    decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '行金额 = 数量 × 单价',
  `tenant_id`      bigint       NOT NULL DEFAULT 0,
  `created_by`     bigint       NULL,
  `created_at`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`     bigint       NULL,
  `updated_at`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `version`        int          NOT NULL DEFAULT 0,
  `ext`            json         NULL,
  `is_deleted`     tinyint(1)   NOT NULL DEFAULT 0,
  `deleted_at`     datetime     NULL,
  `deleted_by`     bigint       NULL,
  PRIMARY KEY (`id`),
  KEY `idx_return_line_return` (`return_id`),
  KEY `idx_return_line_shipment_line` (`shipment_line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货明细行（V67：按行退，同构 fin_shipment_line）';

-- ========== 2. 三级审批流程定义（启动时 ProcessDefAutoDeployRunner 自动部署） ==========
-- 字典 return_approval_status 的中间态即三级：SUPERVISOR_APPROVING → FINANCE_APPROVING → MANAGER_APPROVING。
-- 首节点 rejectable=false（无人可退）；后两级 true（可驳回终止）。
-- flowable_deployment_id 置 NULL = 待自动部署。
INSERT INTO `sys_process_def`
    (process_code, process_name, flowable_deployment_id, def_version, status, node_list, remark, tenant_id)
VALUES
    ('return_approval', '退货审批', NULL, 1, 'ENABLED',
     '[
       {"seq":1,"nodeId":"node_1","name":"主管审批","type":"ROLE","assignee":"ROLE_SUPERVISOR","rejectable":false},
       {"seq":2,"nodeId":"node_2","name":"财务审批","type":"ROLE","assignee":"ROLE_FINANCE","rejectable":true},
       {"seq":3,"nodeId":"node_3","name":"经理审批","type":"ROLE","assignee":"ROLE_SALES_MANAGER","rejectable":true}
     ]',
     'V67 种子：退货三级 ROLE 审批（主管→财务→经理）。末节点通过→自动执行三冲减链（红字应收单/红字发票/红字收款单，按条件跳过）。',
     0)
ON DUPLICATE KEY UPDATE
    process_name = VALUES(process_name),
    status      = VALUES(status),
    node_list   = VALUES(node_list),
    remark      = VALUES(remark);

-- ========== 3. 字典补值 ==========
-- 3.1 receipt_type 补 REFUND（虎哥决策#2：红字收款单做退款单，receiptType=REFUND 与普通红冲单区分）
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('receipt_type', '退货退款', 'REFUND', 4, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);

-- 3.2 receivable_source 补 FROM_RETURN（红字应收单来源：退货生成，与 FROM_SHIPMENT 区分）
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('receivable_source', '退货生成', 'FROM_RETURN', 2, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);

-- 3.3 return_approval_status 补 CANCELLED（撤销终态，同 V56 shipment_status 补 CANCELLED 先例；
--     撤销≠驳回：撤回是申请人主动动作，语义上单列，不挪用 REJECTED）
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('return_approval_status', '已撤销', 'CANCELLED', 9, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);

-- ========== 4. 通知模板 4 个（变量对齐业务层 NotifyCommand.vars） ==========
-- RETURN_SUBMITTED  {{returnNo}} {{projectName}} {{amount}} {{applicant}}
-- RETURN_APPROVED  {{returnNo}} {{projectName}} {{amount}}
-- RETURN_REJECTED  {{returnNo}} {{projectName}} {{approver}} {{reason}}
-- RETURN_WITHDRAWN {{returnNo}} {{projectName}} {{operator}}
INSERT INTO `sys_notification_template`
    (`code`, `type`, `template_name`, `title_tpl`, `content_tpl`, `enabled`, `tenant_id`) VALUES
('RETURN_SUBMITTED',  'RETURN_SUBMITTED',  '退货申请待审批',
 '退货申请待审批：{{returnNo}}',
 '{{applicant}} 发起退货申请 {{returnNo}}（{{projectName}}，金额 ¥{{amount}}），请及时审批。', 1, 0),
('RETURN_APPROVED',  'RETURN_APPROVED',  '退货申请已通过',
 '退货申请已通过：{{returnNo}}',
 '您的退货申请 {{returnNo}}（{{projectName}}，金额 ¥{{amount}}）已审批通过，冲减已执行。', 1, 0),
('RETURN_REJECTED',  'RETURN_REJECTED',  '退货申请被驳回',
 '退货申请被驳回：{{returnNo}}',
 '{{approver}} 驳回了退货申请 {{returnNo}}（{{projectName}}）。理由：{{reason}}', 1, 0),
('RETURN_WITHDRAWN', 'RETURN_WITHDRAWN', '退货申请已撤销',
 '退货申请已撤销：{{returnNo}}',
 '{{operator}} 撤销了退货申请 {{returnNo}}（{{projectName}}），审批流程已终止。', 1, 0)
ON DUPLICATE KEY UPDATE
    `type`          = VALUES(`type`),
    `template_name` = VALUES(`template_name`),
    `title_tpl`     = VALUES(`title_tpl`),
    `content_tpl`   = VALUES(`content_tpl`),
    `enabled`       = VALUES(`enabled`);
