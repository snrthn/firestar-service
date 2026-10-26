-- =====================================================================
-- V75：审批流状态字典通用化（B-重）
-- 目标：5 条审批流（立项/发货/收款/退货/发票）的「审批流状态」共用一套
--       通用 approval_status 字典；将审批节点态（出纳/上级/财务/主管审批中）
--       与业务结果态（已收款/执行中/已完成/已开票）从 approval_status 中剥离。
--
-- 设计要点：
--  1) 通用 approval_status 收录 DRAFT / PENDING_APPROVAL / APPROVING /
--     REJECTED / APPROVED / CANCELLED（立项/发货用 DRAFT，收款用
--     PENDING_APPROVAL，同义不同名，均表示「未提交流程」）。
--  2) 审批节点态（CASHIER_APPROVING / SUPERVISOR_APPROVING /
--     FINANCE_APPROVING / MANAGER_APPROVING / ISSUING）→ 统一 APPROVING，
--     层级信息改由 Flowable 当前节点名（currentNodeName）表达，不进字典。
--  3) 业务结果态剥离：
--       - 收款 RECEIVED → 新增 receipt_status 业务态列（fin_receipt）
--         该列审批通过时落 RECEIVED，approval_status 落 APPROVED。
--       - 退货 EXECUTING / COMPLETED → 已有 execute_status 列
--         （字典 return_execute_status），approval_status 落 APPROVED。
--       - 发票 ISSUED（已开票 = 审批通过）→ APPROVED，不单列业务态。
--  4) 删除各业务独立审批字典 project_approval_status /
--     shipment_approval_status / return_approval_status / invoice_status
--     （已执行的 V73 字典由本迁移删除，不回改 V73 内容，避免 Flyway
--      checksum 失配）。
--  5) fin_invoice.invoice_status 列改名为 approval_status（彻底统一命名）。
--
-- 幂等：所有 INSERT 用 WHERE NOT EXISTS；ALTER 用 information_schema 探测；
--       UPDATE 用 WHERE 限定仅命中旧值；DELETE 仅删被本迁移接管的字典。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 段1：通用 approval_status 字典（type + 6 data）
-- ---------------------------------------------------------------------
INSERT INTO sys_dict_type (type_code, type_name, status, remark, tenant_id)
SELECT 'approval_status', '审批状态', 'ENABLED', '通用审批流状态（立项/发货/收款/退货/发票共用）', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE type_code = 'approval_status');

INSERT INTO sys_dict_data (type_code, dict_label, dict_value, sort_no, style, is_default, status, tenant_id)
SELECT 'approval_status', '草稿', 'DRAFT', 1, 'info', 0, 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'approval_status' AND dict_value = 'DRAFT');

INSERT INTO sys_dict_data (type_code, dict_label, dict_value, sort_no, style, is_default, status, tenant_id)
SELECT 'approval_status', '待审批', 'PENDING_APPROVAL', 2, 'info', 0, 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'approval_status' AND dict_value = 'PENDING_APPROVAL');

INSERT INTO sys_dict_data (type_code, dict_label, dict_value, sort_no, style, is_default, status, tenant_id)
SELECT 'approval_status', '审批中', 'APPROVING', 3, 'warning', 0, 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'approval_status' AND dict_value = 'APPROVING');

INSERT INTO sys_dict_data (type_code, dict_label, dict_value, sort_no, style, is_default, status, tenant_id)
SELECT 'approval_status', '已驳回', 'REJECTED', 4, 'danger', 0, 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'approval_status' AND dict_value = 'REJECTED');

INSERT INTO sys_dict_data (type_code, dict_label, dict_value, sort_no, style, is_default, status, tenant_id)
SELECT 'approval_status', '审批通过', 'APPROVED', 5, 'success', 0, 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'approval_status' AND dict_value = 'APPROVED');

INSERT INTO sys_dict_data (type_code, dict_label, dict_value, sort_no, style, is_default, status, tenant_id)
SELECT 'approval_status', '已撤销', 'CANCELLED', 6, 'info', 0, 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'approval_status' AND dict_value = 'CANCELLED');

-- ---------------------------------------------------------------------
-- 段2：fin_invoice.invoice_status → approval_status（彻底统一命名）+ 值映射
-- ---------------------------------------------------------------------
SET @col_invoice = (SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'firestar' AND table_name = 'fin_invoice' AND column_name = 'invoice_status');
SET @sql_invoice = IF(@col_invoice > 0,
    'ALTER TABLE fin_invoice CHANGE invoice_status approval_status VARCHAR(32) NOT NULL DEFAULT ''PENDING_APPROVAL''',
    'SELECT 1');
PREPARE stmt_invoice FROM @sql_invoice;
EXECUTE stmt_invoice;
DEALLOCATE PREPARE stmt_invoice;

-- 值映射：PENDING→DRAFT（草稿）；ISSUING→APPROVING（审批中）；ISSUED→APPROVED（审批通过=已开票）
UPDATE fin_invoice SET approval_status = 'DRAFT'    WHERE approval_status = 'PENDING';
UPDATE fin_invoice SET approval_status = 'APPROVING' WHERE approval_status = 'ISSUING';
UPDATE fin_invoice SET approval_status = 'APPROVED'  WHERE approval_status = 'ISSUED';

-- ---------------------------------------------------------------------
-- 段3：fin_receipt 新增 receipt_status 业务态列 + 拆分 RECEIVED / CASHIER_APPROVING
-- ---------------------------------------------------------------------
SET @col_receipt = (SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'firestar' AND table_name = 'fin_receipt' AND column_name = 'receipt_status');
SET @sql_receipt = IF(@col_receipt = 0,
    'ALTER TABLE fin_receipt ADD COLUMN receipt_status VARCHAR(32) NULL',
    'SELECT 1');
PREPARE stmt_receipt FROM @sql_receipt;
EXECUTE stmt_receipt;
DEALLOCATE PREPARE stmt_receipt;

-- 业务结果态 RECEIVED 移出 approval_status → receipt_status 列，approval_status 落 APPROVED
UPDATE fin_receipt SET receipt_status = 'RECEIVED', approval_status = 'APPROVED' WHERE approval_status = 'RECEIVED';
-- 审批节点态 CASHIER_APPROVING → APPROVING（层级信息改由 currentNodeName 表达）
UPDATE fin_receipt SET approval_status = 'APPROVING' WHERE approval_status = 'CASHIER_APPROVING';

-- ---------------------------------------------------------------------
-- 段4：fin_return_order 节点态 / 执行态移出 approval_status（执行态已有 execute_status）
-- ---------------------------------------------------------------------
-- 三级审批节点态 → APPROVING
UPDATE fin_return_order SET approval_status = 'APPROVING'
WHERE approval_status IN ('SUPERVISOR_APPROVING', 'FINANCE_APPROVING', 'MANAGER_APPROVING');
-- 业务执行态 EXECUTING / COMPLETED 已在 execute_status 列，approval_status 落 APPROVED
UPDATE fin_return_order SET approval_status = 'APPROVED'
WHERE approval_status IN ('EXECUTING', 'COMPLETED');

-- ---------------------------------------------------------------------
-- 段5：删除被本迁移接管的各业务独立审批字典
-- ---------------------------------------------------------------------
DELETE FROM sys_dict_data
WHERE type_code IN ('project_approval_status', 'shipment_approval_status', 'return_approval_status', 'invoice_status');
DELETE FROM sys_dict_type
WHERE type_code IN ('project_approval_status', 'shipment_approval_status', 'return_approval_status', 'invoice_status');
-- receipt_status 仅保留审批骨架（PENDING_APPROVAL / REJECTED / CANCELLED），删节点态与业务态
DELETE FROM sys_dict_data
WHERE type_code = 'receipt_status'
  AND dict_value IN ('CASHIER_APPROVING', 'RECEIVED', 'PARTIAL_WRITEOFF', 'FULL_WRITEOFF');

-- ---------------------------------------------------------------------
-- 段6：新建 receipt_business_status 字典（收款业务结果态）
-- ---------------------------------------------------------------------
INSERT INTO sys_dict_type (type_code, type_name, status, remark, tenant_id)
SELECT 'receipt_business_status', '收款业务状态', 'ENABLED', '收款单业务结果态（审批通过后为已收款）', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE type_code = 'receipt_business_status');

INSERT INTO sys_dict_data (type_code, dict_label, dict_value, sort_no, style, is_default, status, tenant_id)
SELECT 'receipt_business_status', '已收款', 'RECEIVED', 1, 'success', 0, 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'receipt_business_status' AND dict_value = 'RECEIVED');
