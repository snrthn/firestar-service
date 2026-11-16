-- ============================================================================
-- 【仅集成测试 classpath】补偿 V27 在当前库重放时被跳过的 DDL
--
-- 【2026-11-16 修订 · P1 run4 实锤】原补偿条件是「当前库缺 ext 就补」，
-- 与 V27 的守卫口径不一致，在「全新实例（不存在 firestar 库）」下
-- 会与 V27 抢同一个 DDL → Duplicate column name 'ext' → 79 个用例
-- 因 ApplicationContext 加载失败而 ERROR。
--
-- V27 的真实守卫（逐字复刻，不可改：checksum 铁律，dev/pre/prod 已登记）：
--     IF NOT EXISTS (SELECT 1 FROM information_schema.columns
--                     WHERE table_schema = 'firestar' AND table_name = tbl
--                       AND column_name = 'ext')
--     THEN ALTER TABLE `<当前库>.<tbl>` ADD COLUMN `ext` ...
--
-- 缺陷本质：V27 的【判断作用域 = firestar 库】，但【DDL 作用于当前库】，
-- 二者不一致。这在「当前库就是 firestar」时恰好自洽，在任何别的库名下失配。
--
-- 行为矩阵（当前库 = firestar_test）：
--   ┌───────────────────────────┬───────────────┬────────────────────────┐
--   │ 环境                      │ V27 动作      │ 本补偿动作             │
--   ├───────────────────────────┼───────────────┼────────────────────────┤
--   │ A: firestar 库存在，且    │ 跳过          │ 补（当前库缺 ext 时）  │
--   │    firestar.<tbl>.ext 存在│ （守卫命中）  │                        │
--   ├───────────────────────────┼───────────────┼────────────────────────┤
--   │ B: 无 firestar 库         │ 执行 ADD      │ 让路（不补，避免重复）│
--   │    （全新实例 / P1 的 3308）│              │                        │
--   └───────────────────────────┴───────────────┴────────────────────────┘
--   → 本补偿条件 = 「V27 会跳过该 DDL」AND「当前库确实缺该列」，
--     两种环境下该 DDL 都恰好执行一次。
--
-- 幂等：当前库用 DATABASE() 判定，不写死库名；
--       查询 firestar 库仅只读 information_schema，不做任何写操作。
-- ============================================================================
SET @db = DATABASE();

-- ① sys_agreement
SET @sql = (SELECT IF(
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = 'firestar' AND table_name = 'sys_agreement' AND column_name = 'ext') > 0
      AND
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = @db AND table_name = 'sys_agreement' AND column_name = 'ext') = 0,
    'ALTER TABLE `sys_agreement` ADD COLUMN `ext` JSON NULL COMMENT ''扩展字段''', 'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② sys_agreement_version
SET @sql = (SELECT IF(
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = 'firestar' AND table_name = 'sys_agreement_version' AND column_name = 'ext') > 0
      AND
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = @db AND table_name = 'sys_agreement_version' AND column_name = 'ext') = 0,
    'ALTER TABLE `sys_agreement_version` ADD COLUMN `ext` JSON NULL COMMENT ''扩展字段''', 'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ③ sys_agreement_consent
SET @sql = (SELECT IF(
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = 'firestar' AND table_name = 'sys_agreement_consent' AND column_name = 'ext') > 0
      AND
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = @db AND table_name = 'sys_agreement_consent' AND column_name = 'ext') = 0,
    'ALTER TABLE `sys_agreement_consent` ADD COLUMN `ext` JSON NULL COMMENT ''扩展字段''', 'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
