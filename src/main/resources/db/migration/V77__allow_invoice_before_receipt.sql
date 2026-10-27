-- P1（2026-10-27）：立项补充布尔字段 allow_invoice_before_receipt，
-- 控制是否允许「回款前开出发票」。
--
-- 业务背景：先款后票（不允许回款前开票）的项目，开票额必须以已回款额为上限，
-- 否则会出现「票开超了但钱没回」的账实不符（票款勾稽缺口）。
-- 该字段为 OFF(0) 时，发票侧 saveAllocations / 审批回写双重强制：
--   本次开票额 ≤ 应收单(已回款 − 已开票)余额。
--
-- 默认 1（允许），保留一期既有「先开票后回款」宽松行为，不破坏历史数据。
--
-- 幂等性：用 information_schema 守卫，5.7 / 8 通用（MySQL 不支持 ADD COLUMN IF NOT EXISTS）。
-- 本迁移成功执行后 Flyway 不再重跑；若因故 repair 重跑，列已存在则跳过。

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name   = 'fin_project'
      AND column_name  = 'allow_invoice_before_receipt'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE fin_project ADD COLUMN allow_invoice_before_receipt TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''是否允许回款前开票：1=允许(默认) 0=不允许(强制票款勾稽：开票额≤已回款额)''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
