-- ============================================================================
-- V215：AI 模型配置列表排序（sort_no）
--
-- 背景：【模型配置】页原先按主键 id 顺序展示，管理员无法调整展示优先级
--       （例如把常用的 chat 供应商排到最前）。本迁移新增 sort_no 列，
--       列表按 (sort_no, id) 升序，页面提供上移/下移按钮做整表重排。
--
-- 设计要点：
--   - NOT NULL DEFAULT 0：存量行加列后统一为 0，紧接着按 id 回填，
--     保证升级后展示顺序与原来「按 id 升序」完全一致，用户无感。
--   - 排序号步长 10（10/20/30…），由 Service 层整组重排时写入，留出插入空间。
--   - 不新增表、不动字典，DatabaseCheckService 的 EXPECTED_* 常量不变
--     （按 db-check-baseline.properties 约定，只有动 dict/config/role 行数或新建
--     业务表才需要声明基线标记，本迁移不涉及）。
--   - 幂等：ADD COLUMN 走 information_schema 存在性判断，可重复执行；
--     本脚本内不含 Flyway 占位符（美元符号 + 左花括号）形式，避免解析期替换失败。
-- 依赖：V99（sys_model_config）→ V214（model_type）→ 本迁移。
-- ============================================================================

-- ---------- 1. 新增排序列 ----------
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_model_config' AND COLUMN_NAME = 'sort_no'
);
SET @sql_add_col = IF(@col_exists = 0,
  'ALTER TABLE `sys_model_config` ADD COLUMN `sort_no` INT NOT NULL DEFAULT 0 COMMENT ''列表排序号(升序，整十步长)'' AFTER `model`',
  'SELECT 1');
PREPARE stmt FROM @sql_add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 存量行回填 ----------
-- 按 id 升序 × 10 回填：既保持原「按 id 展示」的顺序不变，又与页面重排写入的
-- 10/20/30… 同一口径（后续新增取 max+10，不会出现 1x / 10x 混排的排序歧义）。
-- 页面重排不会产生 0，故本句仅在刚加列（或历史遗留 0 值）时生效，可重复执行。
UPDATE `sys_model_config` SET `sort_no` = `id` * 10 WHERE `sort_no` = 0;
