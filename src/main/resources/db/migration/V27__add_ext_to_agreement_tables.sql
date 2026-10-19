-- ============================================================================
-- V27：补齐协议三表的 ext 列（与全线 25 张表一致）
-- 根因：V25 建 sys_agreement / sys_agreement_version / sys_agreement_consent 时
-- 漏了 BaseEntity 的 ext（JSON 扩展字段），MyBatis-Plus 自动拼列名 SELECT ... ext
-- 报 Unknown column 'ext' → 所有协议查询（listActive 等）500，前端表现为
-- "请先确认协议已初始化"。
-- 修复：给三表补 ext JSON 列（可空，与基线一致）。
-- 幂等：用 information_schema 守卫 + 存储过程，手动执行 / Flyway 重跑均安全。
-- （V25 文件已记录且永不修改，故走 V27 增量；ALTER 不进 Java 编译范围，需重打包生效）
-- ============================================================================

DROP PROCEDURE IF EXISTS add_ext_if_missing;
DELIMITER $$
CREATE PROCEDURE add_ext_if_missing(IN tbl VARCHAR(64))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'firestar' AND table_name = tbl AND column_name = 'ext'
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `ext` JSON NULL');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_ext_if_missing('sys_agreement');
CALL add_ext_if_missing('sys_agreement_version');
CALL add_ext_if_missing('sys_agreement_consent');
DROP PROCEDURE IF EXISTS add_ext_if_missing;
