-- ============================================================================
-- V215：AI 模型配置列表排序（sort_no）
--
-- 背景：【模型配置】页原先按主键 id 顺序展示，管理员无法调整展示优先级
--       （例如把常用的 chat 供应商排到最前）。本迁移新增 sort_no 列，
--       列表按 (sort_no, id) 升序，页面提供上移/下移按钮做整表重排。
--
-- 设计要点：
--   - NOT NULL DEFAULT 0：存量行加列后统一为 0，紧接着按 id 升序密排回填 1..N，
--     保证升级后展示顺序与原来「按 id 升序」完全一致，且序号就是用户肉眼看到的
--     第几行（不做 10/20/30 的「整十步长」——这里是整表重排，用不上插入空隙，
--     用户反馈 10 倍数看着莫名其妙，改为连续序号）。
--   - 排序号由 Service 层维护：新增默认追加到末尾（max+1），表单可直接填写，
--     页面「上移/下移」整表重排回写 1..N。
--   - 不新增表、不动字典，DatabaseCheckService 的 EXPECTED_* 常量不变
--     （按 db-check-baseline.properties 约定，只有动 dict/config/role 行数或新建
--     业务表才需要声明基线标记，本迁移不涉及）。
--   - 回填是**无条件**重写：本脚本随 Flyway 只跑一次；本地删 flyway_schema_history
--     记录重跑 = 刻意把排序重置回 id 顺序（应用层接管后会再被页面重排覆盖）。
--   - ADD COLUMN 走 information_schema 存在性判断；本脚本内不含 Flyway 占位符
--     （美元符号 + 左花括号）形式，避免解析期替换失败。
-- 依赖：V99（sys_model_config）→ V214（model_type）→ 本迁移。
-- ============================================================================

-- ---------- 1. 新增排序列 ----------
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_model_config' AND COLUMN_NAME = 'sort_no'
);
SET @sql_add_col = IF(@col_exists = 0,
  'ALTER TABLE `sys_model_config` ADD COLUMN `sort_no` INT NOT NULL DEFAULT 0 COMMENT ''列表排序号(升序，1..N 连续)'' AFTER `model`',
  'SELECT 1');
PREPARE stmt FROM @sql_add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 存量行密排回填 ----------
-- 按 id 升序编 1..N（ROW_NUMBER 语义）：顺序与原「按 id 展示」一致，数值即展示序号。
-- 无条件重写：删 flyway 记录重跑本脚本 = 刻意重置排序，属预期行为。
SET @rn := 0;
UPDATE `sys_model_config` SET `sort_no` = (@rn := @rn + 1) ORDER BY `id`;
