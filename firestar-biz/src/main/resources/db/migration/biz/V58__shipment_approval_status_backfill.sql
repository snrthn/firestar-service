-- ============================================================================
-- V58：shipment approval_status 存量回填（V57 的修正补充）。
-- 背景：V57 加 approval_status 列时 DEFAULT='APPROVING'，对「迁移前已审批通过」的单是错的
--   （会把它们卡在「不可标记发货」的新闸门外）。V57 已应用且不可再改（Flyway checksum 校验，
--   改文件 = 启动失败），故回填逻辑单独走本增量版本。
-- 幂等：WHERE approval_status='APPROVING' 守卫，重复执行/手动预跑均安全（命中 0 行无副作用）。
--
-- ⚠️ 2026-10-24 生产事故修复（第二版）：第一版直接引用 `act_ru_execution` 小写表名，
--   Windows 本地 MySQL 表名大小写不敏感所以本地验证通过，但生产是 Linux MySQL（大小写敏感），
--   Flowable 实际建表名为大写 `ACT_RU_EXECUTION` → 生产启动报 1146 表不存在 → 后端起不来、
--   云效 run#64 部署 FAIL。本版改为：information_schema 大小写无关查找真名 + 存储过程动态 SQL，
--   表缺失则跳过该语句，绝不再因表名硬编码让迁移失败。范式同 V27（information_schema 守卫 + 过程）。
-- ============================================================================

DROP PROCEDURE IF EXISTS backfill_shipment_approved;

DELIMITER $$
CREATE PROCEDURE backfill_shipment_approved()
BEGIN
    DECLARE runtime_tbl VARCHAR(64) DEFAULT NULL;

    -- 大小写无关地定位 Flowable 运行时表真名（本地 Windows 不敏感 / 生产 Linux 敏感统一兼容）
    SELECT table_name INTO runtime_tbl
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND LOWER(table_name) = 'act_ru_execution'
    LIMIT 1;

    IF runtime_tbl IS NOT NULL THEN
        -- 判定口径：运行时表无该实例记录 = 实例已终结 = 审批已走完
        SET @sql = CONCAT(
            'UPDATE `fin_shipment` SET `approval_status` = ''APPROVED'' ',
            'WHERE `approval_status` = ''APPROVING'' ',
            'AND `shipment_status` = ''PENDING'' ',
            'AND `process_instance_id` IS NOT NULL ',
            'AND NOT EXISTS (SELECT 1 FROM `', runtime_tbl, '` e ',
            'WHERE e.`PROC_INST_ID_` = `fin_shipment`.`process_instance_id`)'
        );
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL backfill_shipment_approved();
DROP PROCEDURE IF EXISTS backfill_shipment_approved;

-- 驳回/撤回归 shipment_status 直接映射（不依赖 Flowable 表，无环境差异风险）
UPDATE `fin_shipment` SET `approval_status` = 'REJECTED'
WHERE `approval_status` = 'APPROVING' AND `shipment_status` = 'REJECTED';

UPDATE `fin_shipment` SET `approval_status` = 'CANCELLED'
WHERE `approval_status` = 'APPROVING' AND `shipment_status` = 'CANCELLED';
