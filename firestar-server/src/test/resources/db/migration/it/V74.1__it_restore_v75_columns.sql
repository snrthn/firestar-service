-- ============================================================================
-- 【仅集成测试 classpath】补偿 V75 在当前库重放时跳过的两处 DDL
--
-- 【2026-11-16 修订 · P1 run4 实锤】原补偿是「按当前库真实状态无条件补齐」，
-- 与 V75 的守卫口径不一致：
--   ① fin_invoice 改名这一处恰好兼容（两种环境下 V75 都会跳过 CHANGE），
--      保留「按当前库真实状态补齐」；
--   ② fin_receipt 加列这一处不兼容 —— 在「全新实例（不存在 firestar 库）」下
--      V75 会执行 ADD COLUMN，而本补偿已经加过 → Duplicate column name 'receipt_status'。
--
-- V75 的真实守卫（逐字复刻，不可改：checksum 铁律，dev/pre/prod 已登记）：
--   段2  SET @col_invoice = COUNT(firestar.fin_invoice.invoice_status);
--        IF @col_invoice > 0 THEN CHANGE invoice_status -> approval_status
--   段3  SET @col_receipt = COUNT(firestar.fin_receipt.receipt_status);
--        IF @col_receipt = 0 THEN ADD COLUMN receipt_status
--
-- 缺陷本质：V75 的【判断作用域 = firestar 库】，但【DDL 作用于当前库】。
-- 段2 与段3 的判定方向相反 —— 段2 是「旧列在就改名」，段3 是「新列不在就加」，
-- 所以补偿条件必须分别对齐，不能套同一个模板。
--
-- 行为矩阵（当前库 = firestar_test）：
--   ┌────────────────────────────┬──────────────────┬──────────────────────┐
--   │ 环境                       │ V75 动作         │ 本补偿动作           │
--   ├────────────────────────────┼──────────────────┼──────────────────────┤
--   │ A: firestar 库存在         │ 段2 跳过         │ ① 补（当前库有旧列） │
--   │   （dev 已跑过 V75）       │ 段3 跳过         │ ② 补（当前库缺新列） │
--   ├────────────────────────────┼──────────────────┼──────────────────────┤
--   │ B: 无 firestar 库          │ 段2 跳过         │ ① 补（当前库有旧列） │
--   │   （全新实例 / P1 的 3308） │ 段3 执行 ADD     │ ② 让路（避免重复）  │
--   └────────────────────────────┴──────────────────┴──────────────────────┘
--   → 每个 DDL 的补偿条件 = 「V75 会跳过该 DDL」AND「当前库确实需要该 DDL」。
--
-- 幂等：当前库用 DATABASE() 判定，不写死库名；
--       查询 firestar 库仅只读 information_schema，不做任何写操作。
-- ============================================================================
SET @db = DATABASE();

-- ① fin_invoice: invoice_status -> approval_status（与 V75 段2 的定义一致）
--    V75 段2 会跳过 ⟺ firestar.fin_invoice.invoice_status 不存在；
--    本补偿在该前提下，按当前库真实状态把旧列改名为 approval_status。
SET @sql = (SELECT IF(
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = 'firestar' AND table_name = 'fin_invoice' AND column_name = 'invoice_status') = 0
      AND
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = @db AND table_name = 'fin_invoice' AND column_name = 'invoice_status') > 0
      AND
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = @db AND table_name = 'fin_invoice' AND column_name = 'approval_status') = 0,
    'ALTER TABLE `fin_invoice` CHANGE `invoice_status` `approval_status` VARCHAR(32) NOT NULL DEFAULT ''PENDING_APPROVAL'' COMMENT ''审批状态（通用 approval_status 字典）''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② fin_receipt: 新增 receipt_status 业务态列（与 V75 段3 的定义一致）
--    V75 段3 会跳过 ⟺ firestar.fin_receipt.receipt_status 存在；
--    本补偿仅在该前提下补当前库，否则让路给 V75 自己加。
SET @sql = (SELECT IF(
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = 'firestar' AND table_name = 'fin_receipt' AND column_name = 'receipt_status') > 0
      AND
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = @db AND table_name = 'fin_receipt' AND column_name = 'receipt_status') = 0,
    'ALTER TABLE `fin_receipt` ADD COLUMN `receipt_status` VARCHAR(32) NULL',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
