-- ============================================================
-- V66：修复 V65 存量数据初始化遗漏（2026-10-25 虎哥：SK-003 补核销的前置修复）
--
-- 背景（线上两问题收尾时新暴露的数据缺口）：
--   V65 用 ALTER TABLE ... ADD COLUMN ... DEFAULT 0.00 给 fin_receivable 加
--   received_amount / unreceived_amount 两列。MySQL 对 ADD COLUMN 的语义是
--   「存量行取 DEFAULT 值」——5 条 V65 之前创建的应收单 unreceived_amount 全被
--   刷成 0.00（本应 = receivable_amount，语义「一分未收」）。
--   直接后果：核销分配校验「每行 ≤ 该应收单未回款余额」对全部老单恒不成立，
--   事后核销（allocate）与新建分配都会被拦死，必须回填后才能走业务。
--   另：V65 之前审批通过的收款单，written_off/unwritten_off 从未被写过
--   （applyWriteOffs 是 V65 才有的逻辑），RECEIVED 且无核销行的老单
--   unwritten_off_amount=0.00（本应 = receipt_amount）。
--
-- 幂等性（两条都可重复执行，无副作用）：
--   ① unreceived ≡ receivable_amount - received_amount 是本系统不变量——
--      applyWriteOffs / 红冲反向 / allocate 均成对维护两列，恒等式永远成立；
--      已正确的行 WHERE 不命中。
--   ② 只命中「RECEIVED 且 written=0 且无 NORMAL 核销行」的老单；一旦业务
--      写过核销（written>0 或有核销行）即不再命中；后续 allocate 会重写这两列。
--
-- 表数量账不变（无新表）；不新增字典行。
-- ============================================================

-- 1. 应收单未回款金额按恒等式回填（修复 V65 加列默认值 0 的存量污染）
UPDATE `fin_receivable`
SET `unreceived_amount` = `receivable_amount` - `received_amount`
WHERE `unreceived_amount` <> `receivable_amount` - `received_amount`;

-- 2. V65 前已审批通过且无核销行的收款单：未核销金额回填为全额
UPDATE `fin_receipt` r
SET r.`unwritten_off_amount` = r.`receipt_amount`
WHERE r.`approval_status` = 'RECEIVED'
  AND r.`written_off_amount` = 0
  AND NOT EXISTS (
      SELECT 1 FROM `fin_write_off` w
      WHERE w.`receipt_id` = r.`id` AND w.`status` = 'NORMAL'
  );
