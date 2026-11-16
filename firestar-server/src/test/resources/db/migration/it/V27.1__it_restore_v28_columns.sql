-- ============================================================================
-- 【仅集成测试 classpath】补偿 V28 在当前库重放时被跳过的 DDL
--
-- 【2026-11-16 修订 · P1 run4 实锤】原补偿条件是「按当前库真实状态无条件补齐」，
-- 与 V28 的守卫口径不一致，在「全新实例（不存在 firestar 库）」下
-- 会与 V28 抢同一个 DDL → Duplicate column name 'avatar_url'。
--
-- V28 的真实守卫（逐字复刻，不可改：checksum 铁律，dev/pre/prod 已登记）：
--   ① IF NOT EXISTS (SELECT 1 FROM information_schema.columns
--                     WHERE table_schema='firestar' AND table_name='sys_user'
--                       AND column_name='avatar_url')
--      THEN ALTER TABLE sys_user ADD COLUMN avatar_url ...
--   ② IF EXISTS (SELECT 1 FROM information_schema.columns
--                 WHERE table_schema='firestar' AND table_name='sys_config'
--                   AND column_name='config_value' AND column_type<>'mediumtext')
--      THEN ALTER TABLE sys_config MODIFY COLUMN config_value MEDIUMTEXT ...
--
-- 缺陷本质：V28 的【判断作用域 = firestar 库】，但【DDL 作用于当前库】。
-- 注意 ① 与 ② 的方向相反 —— ① 是「列存在就跳过」，② 是「列存在且类型不对才改」，
-- 因此补偿条件不能套同一个模板，必须分别对齐。
--
-- 行为矩阵（当前库 = firestar_test）：
--   ┌────────────────────────────┬──────────────┬────────────────────────┐
--   │ 环境                       │ V28 动作     │ 本补偿动作             │
--   ├────────────────────────────┼──────────────┼────────────────────────┤
--   │ A: firestar 库存在         │ ① 跳过       │ ① 补（当前库缺列时）  │
--   │   （dev 已跑过 V28）       │ ② 跳过       │ ② 补（当前库类型不对）│
--   ├────────────────────────────┼──────────────┼────────────────────────┤
--   │ B: 无 firestar 库          │ ① 执行 ADD   │ ① 让路（避免重复）    │
--   │   （全新实例 / P1 的 3308） │ ② 跳过       │ ② 补（当前库类型不对）│
--   └────────────────────────────┴──────────────┴────────────────────────┘
--   → 每个 DDL 的补偿条件 = 「V28 会跳过该 DDL」AND「当前库确实需要该 DDL」。
--
-- 幂等：当前库用 DATABASE() 判定，不写死库名；
--       查询 firestar 库仅只读 information_schema，不做任何写操作。
-- ============================================================================
SET @db = DATABASE();

-- ① sys_user.avatar_url
--    V28 ① 会跳过 ⟺ firestar.sys_user.avatar_url 存在；本补偿仅在该前提下补当前库。
SET @sql = (SELECT IF(
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = 'firestar' AND table_name = 'sys_user' AND column_name = 'avatar_url') > 0
      AND
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = @db AND table_name = 'sys_user' AND column_name = 'avatar_url') = 0,
    'ALTER TABLE `sys_user` ADD COLUMN `avatar_url` VARCHAR(255) NULL COMMENT ''头像文件ID'' AFTER `email`',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② sys_config.config_value -> MEDIUMTEXT
--    V28 ② 会执行 ⟺ firestar.sys_config.config_value 存在且类型 <> mediumtext；
--    本补偿仅在 V28 ②【跳过】（即该条件不成立）时才补当前库。
SET @sql = (SELECT IF(
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = 'firestar' AND table_name = 'sys_config'
          AND column_name = 'config_value' AND column_type <> 'mediumtext') = 0
      AND
      (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = @db AND table_name = 'sys_config'
          AND column_name = 'config_value' AND column_type <> 'mediumtext') > 0,
    'ALTER TABLE `sys_config` MODIFY COLUMN `config_value` MEDIUMTEXT NULL COMMENT ''参数值（富文本场景）''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
