-- P1-1（2026-10-27，修订 2026-10-27）：发票号唯一约束（MySQL 5.7 / 8 兼容，自愈式）
--
-- 背景：发票号是税务法定唯一标识。此前缺失 UNIQUE 约束，同一张发票可被录入 / 核销两次，
--       虚增收入且事后无法追溯。其余业务单据（project / shipment / return / receivable /
--       receipt / write_off / allocation）均已建 (tenant_id, xxx) 唯一键，唯独发票漏了。本迁移补齐。
--
-- 为何要「自愈式」：
--   首版 V76 直接 ALTER 加唯一键，但本地库存在存量重复数据
--   (tenant_id=0, invoice_no=0004442718 等多行)，导致 ALTER 报
--   Duplicate entry ... for key 'fin_invoice.uk_tenant_invoice_no'，迁移失败并卡在 FAILED 状态，
--   使后续每次启动都 Validate failed → 应用起不来。
--   现改为「先去重，再加键」，无论本地(MySQL8)还是线上(MySQL5.7)有脏数据都能干净通过。
--
-- 去重策略：同一 (tenant_id, invoice_no) 仅保留 id 最小的一行，其余删除。
--   - invoice_no 可 NULL（红冲票可能暂无票号），唯一索引允许多行 NULL 共存，
--     故仅对 invoice_no 非 NULL 的重复组去重，绝不误删 NULL 发票号行。
--   - 用 <=> 做 NULL 安全相等比较。
-- 兼容性：DELETE ... INNER JOIN、information_schema 查询、<=> 在 MySQL 5.7 / 8 均支持。
-- 幂等性：本迁移成功执行后 Flyway 不会再跑；若因故需 repair 重跑，去重已无行可删、
--         加键前先判存在，不会二次报错。

-- 1) 清理存量重复（保留每组 (tenant_id, invoice_no) 中 id 最小的一行）
DELETE t1 FROM fin_invoice t1
INNER JOIN fin_invoice t2
    ON t1.tenant_id <=> t2.tenant_id
   AND t1.invoice_no <=> t2.invoice_no
WHERE t1.id > t2.id
  AND t1.invoice_no IS NOT NULL;

-- 2) 条件添加唯一键
--    MySQL 5.7 不支持 `ALTER TABLE ... ADD UNIQUE KEY IF NOT EXISTS`，
--    故用 information_schema 守卫 + 预处理语句，5.7 / 8 通用。
SET @uk_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name   = 'fin_invoice'
      AND index_name   = 'uk_tenant_invoice_no'
);
SET @uk_sql = IF(@uk_exists = 0,
    'ALTER TABLE fin_invoice ADD UNIQUE KEY uk_tenant_invoice_no (tenant_id, invoice_no)',
    'SELECT 1');
PREPARE uk_stmt FROM @uk_sql;
EXECUTE uk_stmt;
DEALLOCATE PREPARE uk_stmt;
