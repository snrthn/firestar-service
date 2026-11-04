-- ============================================================================
-- V72：删除 fin_project.customer_name 冗余列
-- 依赖：V68（fin_customer 客户主表已建，存量项目按名回填并关联）
--
-- 背景：V68 在 fin_project 上「保留 customer_name 作为快照冗余」。但实现上
--       ProjectServiceImpl.applyCustomer 每次保存都用主表当前名覆盖该列，
--       故它既不是真快照（客户改名后会跟着变），又与 fin_customer.customer_name 重复。
--       本迁移将其删除：展示用的客户名改由 toVO 按 customer_id 联 fin_customer 翻译。
--
-- 幂等：列存在性用 information_schema 判断 + 动态 SQL；删列前先防御性把
--       「customer_id<=0 且有名」的遗留行归档进 fin_customer 并关联（V68 应已处理，
--       此处仅保险，重跑为空操作）；客户编号对 NULL 兜底补齐（与 V68 同构）。
--       删列不增/不删表，DatabaseCheckService 计数常量无需调整。
-- ============================================================================

-- ========== 1. 防御：遗留未关联行先归档客户主表 ==========
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_project' AND COLUMN_NAME = 'customer_name'
);

SET @ddl_archive := IF(@exists_col > 0,
    'INSERT INTO fin_customer (customer_no, customer_name, status, remark, tenant_id) '
    'SELECT NULL, t.customer_name, ''ENABLED'', ''V72 遗留客户名归档'', t.tenant_id '
    'FROM (SELECT DISTINCT p.tenant_id, p.customer_name FROM fin_project p '
    '      WHERE (p.customer_id IS NULL OR p.customer_id <= 0) '
    '        AND p.customer_name IS NOT NULL AND TRIM(p.customer_name) <> '''') t '
    'WHERE NOT EXISTS (SELECT 1 FROM fin_customer c '
    '      WHERE c.customer_name = t.customer_name AND c.tenant_id = t.tenant_id)',
    'SELECT 1');
PREPARE stmt_archive FROM @ddl_archive; EXECUTE stmt_archive; DEALLOCATE PREPARE stmt_archive;

SET @ddl_link := IF(@exists_col > 0,
    'UPDATE fin_project p JOIN fin_customer c '
    '  ON c.customer_name = p.customer_name AND c.tenant_id = p.tenant_id AND c.is_deleted = 0 '
    'SET p.customer_id = c.id '
    'WHERE (p.customer_id IS NULL OR p.customer_id <= 0) '
    '  AND p.customer_name IS NOT NULL AND TRIM(p.customer_name) <> ''''',
    'SELECT 1');
PREPARE stmt_link FROM @ddl_link; EXECUTE stmt_link; DEALLOCATE PREPARE stmt_link;

-- 客户编号兜底（V68/V72 新建的客户若 customer_no 为空则补齐，与 V68 同构）
UPDATE fin_customer
SET customer_no = CONCAT('CUS-', DATE_FORMAT(created_at, '%Y%m%d'), '-', LPAD(id, 4, '0'))
WHERE customer_no IS NULL OR customer_no = '';

-- ========== 2. 删除冗余列 ==========
SET @ddl_drop := IF(@exists_col > 0,
    'ALTER TABLE fin_project DROP COLUMN customer_name',
    'SELECT 1');
PREPARE stmt_drop FROM @ddl_drop; EXECUTE stmt_drop; DEALLOCATE PREPARE stmt_drop;
