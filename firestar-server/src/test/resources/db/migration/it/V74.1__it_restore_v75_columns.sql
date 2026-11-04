-- ============================================================================
-- 【仅集成测试 classpath】补偿 V75 在 firestar_test 库重放时跳过的两处 DDL
--
-- 根因：V75__approval_status_generalize.sql 的两处 information_schema 探测写死
--       `table_schema = 'firestar'`：
--         @col_invoice 查 dev 库的 fin_invoice.invoice_status（dev 已改名，查不到）→ 0
--            → 跳过 CHANGE invoice_status → approval_status
--            → 紧接着 UPDATE fin_invoice ... WHERE approval_status 报 Unknown column。
--         @col_receipt 查 dev 库的 fin_receipt.receipt_status（dev 已加，查得到）→ >0
--            → 跳过 ADD COLUMN，当前库缺列，后续 UPDATE 同样报错。
--       这是「空库全量重放」在 V75 直接失败的直接原因，也意味着任何库名不叫
--       firestar 的新环境从零初始化都会挂在 V75（pre/test/prod 因从 dev dump
--       复制、V75 已登记过不重跑，所以一直没暴露）。
-- 处理：V74.1 在 V75 之前按当前库真实状态补齐这两列，V75 的探测自然落到
--       「无需变更」分支，其后的值映射 UPDATE 得以正常执行。已应用迁移不改。
-- ============================================================================
SET @db = DATABASE();

-- ① fin_invoice: invoice_status -> approval_status（与 V75 段2 的定义一致）
SET @sql = (SELECT IF(
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = @db AND table_name = 'fin_invoice' AND column_name = 'invoice_status') > 0
      AND
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = @db AND table_name = 'fin_invoice' AND column_name = 'approval_status') = 0,
    'ALTER TABLE `fin_invoice` CHANGE `invoice_status` `approval_status` VARCHAR(32) NOT NULL DEFAULT ''PENDING_APPROVAL'' COMMENT ''审批状态（通用 approval_status 字典）''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② fin_receipt: 新增 receipt_status 业务态列（与 V75 段3 的定义一致）
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `fin_receipt` ADD COLUMN `receipt_status` VARCHAR(32) NULL',
    'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'fin_receipt' AND column_name = 'receipt_status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
