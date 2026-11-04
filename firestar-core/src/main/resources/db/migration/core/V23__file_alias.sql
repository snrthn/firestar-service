-- ============================================================
-- V23：sys_file_storage 增加 alias（文件别名）列
--
-- 背景：文件已有「落盘名 fileName（服务端生成）+ 原始名 originalName（浏览器给出）」，
-- 业务还需要一个人可维护的「别名」用于展示与审计溯源（如「10 月报销发票」）。
-- 口径（2026-10-16 与巴比确认）：别名是「每个文件一条」的业务数据，不是全局枚举，
-- 因此不进字典（字典放这个会让 sys_dict_data 无限膨胀），只在文件管理处维护：
-- 上传时填写 / 文件列表编辑；下载日志联动展示，方便审计。
--
-- ⚠️ 三处对齐：DDL（本文件）/ 实体 FileStorage.java / docs/建表方案.md。
-- ⚠️ 本迁移只加列，不动字典与种子数据 → DatabaseCheckService 基线不变（dictType 28 / dictData 104）。
-- ============================================================

SET @db = DATABASE();

SET @has_col = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'sys_file_storage' AND column_name = 'alias');
SET @sql = IF(@has_col = 0,
  'ALTER TABLE `sys_file_storage` ADD COLUMN `alias` VARCHAR(255) NULL COMMENT ''文件别名（文件管理处维护：上传填写/列表编辑；仅展示与审计用，为空回退 original_name）'' AFTER `original_name`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
