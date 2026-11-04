-- ============================================================================
-- V95 发票流程重构：业务单号 + 购方六要素快照
-- ----------------------------------------------------------------------------
-- 背景（虎哥 2026-10-31 拍板）：
--   1) 此前发票「申请」阶段就要求填发票号码/代码/日期并随机生成 FP- 单号，
--      把「申请」与「开票」两个节点拧成一步、且税务发票号随机生成，违背实际业务。
--   2) 购方抬头（六要素）应沉淀在客户主表 fin_customer，新客户填一次、后续自动带出；
--      发票侧只存「申请时的快照」，客户改名/改税号不回溯篡改历史发票抬头。
--   3) 严格区分两类编码：
--        - apply_no：业务申请单号，创建即生成（FP-yyyyMMdd-NNNN），系统唯一、不可空、不可改；
--        - invoice_no：税务发票号，开票节点由开票员手填回填，绝不随机生成。
--   4) 状态机三级：DRAFT(申请草稿) → SUBMITTED(已提交·待开票) → ISSUED(已开票已传票)。
--      审批中态 APPROVING 作为提交到审批通过之间的瞬态保留；金额/核销在 ISSUED 时才滚。
-- ============================================================================

-- ⚠️ 幂等改造背景（2026-10-31 事故复盘，别再踩）：
--    首次提交时 buyer_bank_account 误写为 AFTER buyer_bank_account（把列插在自己之后），
--    MySQL 报 1054 Unknown column → V95 迁移失败、服务起不来。
--    但 MySQL 的 DDL 是隐式提交的：报错前的 ALTER（apply_no / uk_tenant_apply_no / 前 5 个 buyer_* /
--    fin_customer.buyer_phone）已经真实落库，flyway_schema_history 里却留下 success=false 的 V95。
--    此后无论 repair 还是重跑，前几条 ALTER 都会因 duplicate column name 二次失败。
--    故本脚本全部 DDL 改为「information_schema 探测 + IF + PREPARE 动态执行」的幂等写法：
--    列/索引存在即跳过，缺失才建，可安全重复执行。INSERT ... SELECT ... WHERE NOT EXISTS 本身已幂等，保持原样。
--    配套操作：删掉 flyway_schema_history 中 V95 的失败行后重启即可。

-- 1) 客户主表：增加购方电话（发票抬头用，区别于联系人电话 contactPhone）
SET @fs_exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_customer' AND COLUMN_NAME = 'buyer_phone');
SET @fs_ddl := IF(@fs_exist = 0,
    'ALTER TABLE fin_customer ADD COLUMN buyer_phone VARCHAR(64) NULL COMMENT ''购方电话（发票抬头用，区别于联系人电话）'' AFTER bank_account',
    'SELECT 1');
PREPARE fs_stmt FROM @fs_ddl; EXECUTE fs_stmt; DEALLOCATE PREPARE fs_stmt;

-- 2) 发票表：业务申请单号（创建即生成，系统唯一，区别于税务发票号 invoice_no）
SET @fs_exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_invoice' AND COLUMN_NAME = 'apply_no');
SET @fs_ddl := IF(@fs_exist = 0,
    'ALTER TABLE fin_invoice ADD COLUMN apply_no VARCHAR(64) NULL COMMENT ''业务申请单号（创建即生成 FP-yyyyMMdd-NNNN，系统唯一，非税务发票号）'' AFTER receivable_id',
    'SELECT 1');
PREPARE fs_stmt FROM @fs_ddl; EXECUTE fs_stmt; DEALLOCATE PREPARE fs_stmt;

-- 2.1) 租户 + 业务单号唯一键（注意：索引存在性查 information_schema.STATISTICS，不是 COLUMNS）
SET @fs_exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_invoice' AND INDEX_NAME = 'uk_tenant_apply_no');
SET @fs_ddl := IF(@fs_exist = 0,
    'CREATE UNIQUE INDEX uk_tenant_apply_no ON fin_invoice (tenant_id, apply_no)',
    'SELECT 1');
PREPARE fs_stmt FROM @fs_ddl; EXECUTE fs_stmt; DEALLOCATE PREPARE fs_stmt;

-- 3) 发票表：购方六要素快照（申请时从客户表带出，存历史快照，客户信息变更不影响已开发票）
--    每列独立探测，逐列可补；列顺序按 AFTER 前一列，最后一条不能指向自己（本次事故根因）
SET @fs_exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_invoice' AND COLUMN_NAME = 'buyer_name');
SET @fs_ddl := IF(@fs_exist = 0,
    'ALTER TABLE fin_invoice ADD COLUMN buyer_name VARCHAR(255) NULL COMMENT ''购方名称（发票抬头，快照自客户表）'' AFTER apply_no',
    'SELECT 1');
PREPARE fs_stmt FROM @fs_ddl; EXECUTE fs_stmt; DEALLOCATE PREPARE fs_stmt;

SET @fs_exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_invoice' AND COLUMN_NAME = 'buyer_tax_no');
SET @fs_ddl := IF(@fs_exist = 0,
    'ALTER TABLE fin_invoice ADD COLUMN buyer_tax_no VARCHAR(64) NULL COMMENT ''购方纳税人识别号（快照）'' AFTER buyer_name',
    'SELECT 1');
PREPARE fs_stmt FROM @fs_ddl; EXECUTE fs_stmt; DEALLOCATE PREPARE fs_stmt;

SET @fs_exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_invoice' AND COLUMN_NAME = 'buyer_address');
SET @fs_ddl := IF(@fs_exist = 0,
    'ALTER TABLE fin_invoice ADD COLUMN buyer_address VARCHAR(512) NULL COMMENT ''购方地址（快照）'' AFTER buyer_tax_no',
    'SELECT 1');
PREPARE fs_stmt FROM @fs_ddl; EXECUTE fs_stmt; DEALLOCATE PREPARE fs_stmt;

SET @fs_exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_invoice' AND COLUMN_NAME = 'buyer_phone');
SET @fs_ddl := IF(@fs_exist = 0,
    'ALTER TABLE fin_invoice ADD COLUMN buyer_phone VARCHAR(64) NULL COMMENT ''购方电话（快照）'' AFTER buyer_address',
    'SELECT 1');
PREPARE fs_stmt FROM @fs_ddl; EXECUTE fs_stmt; DEALLOCATE PREPARE fs_stmt;

SET @fs_exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_invoice' AND COLUMN_NAME = 'buyer_bank_name');
SET @fs_ddl := IF(@fs_exist = 0,
    'ALTER TABLE fin_invoice ADD COLUMN buyer_bank_name VARCHAR(255) NULL COMMENT ''购方开户银行（快照）'' AFTER buyer_phone',
    'SELECT 1');
PREPARE fs_stmt FROM @fs_ddl; EXECUTE fs_stmt; DEALLOCATE PREPARE fs_stmt;

-- ⚠️ AFTER 必须指向「已存在的上一列」= buyer_bank_name；写 AFTER buyer_bank_account 会报 1054（把自己插在自己后面）
SET @fs_exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_invoice' AND COLUMN_NAME = 'buyer_bank_account');
SET @fs_ddl := IF(@fs_exist = 0,
    'ALTER TABLE fin_invoice ADD COLUMN buyer_bank_account VARCHAR(128) NULL COMMENT ''购方银行账号（快照）'' AFTER buyer_bank_name',
    'SELECT 1');
PREPARE fs_stmt FROM @fs_ddl; EXECUTE fs_stmt; DEALLOCATE PREPARE fs_stmt;

-- 4) approval_status 通用字典补充 SUBMITTED(待开票) / ISSUED(已开票)
-- ⚠️ 列名必须照抄 V75__approval_status_generalize.sql，不要凭印象写
--    （2026-10-31 事故：我写成 dict_sort，真实列是 sort_no → 1054 Unknown column 'dict_sort'，
--      DDL 已经跑完却在字典这步二次失败。凡改这段 INSERT，先去 V75 核对列名。）
--    （type_code, dict_label, dict_value, sort_no, style, is_default, status, tenant_id）
INSERT INTO sys_dict_data (type_code, dict_label, dict_value, sort_no, style, is_default, status, tenant_id)
SELECT 'approval_status', '待开票', 'SUBMITTED', 7, 'warning', 0, 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'approval_status' AND dict_value = 'SUBMITTED');

INSERT INTO sys_dict_data (type_code, dict_label, dict_value, sort_no, style, is_default, status, tenant_id)
SELECT 'approval_status', '已开票', 'ISSUED', 8, 'success', 0, 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'approval_status' AND dict_value = 'ISSUED');

-- 5) 存量回填：V75 曾把原 invoice_status='ISSUED' 映射为 approval_status='APPROVED'。
--    业务语义上这些单已开票完成，统一置为 ISSUED（仅限 fin_invoice 表，不动其他模块）。
UPDATE fin_invoice
SET approval_status = 'ISSUED'
WHERE approval_status = 'APPROVED' AND is_deleted = 0;
