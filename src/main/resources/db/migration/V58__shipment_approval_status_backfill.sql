-- ============================================================================
-- V58：shipment approval_status 存量回填（V57 的修正补充）。
-- 背景：V57 加 approval_status 列时 DEFAULT='APPROVING'，对「迁移前已审批通过」的单是错的
--   （会把它们卡在「不可标记发货」的新闸门外）。V57 已于 2026-10-24 02:53:56 应用且不可
--   再改（Flyway checksum 校验，改文件 = 启动失败），故回填逻辑单独走本增量版本。
-- 判定口径：Flowable 运行时表 act_ru_execution 无该实例记录 = 实例已终结 = 审批已走完。
--   驳回/撤回归 shipment_status 直接映射，不依赖实例表。
-- 幂等：WHERE approval_status='APPROVING' 守卫，重复执行/手动预跑均安全（命中 0 行无副作用）。
-- ============================================================================

UPDATE `fin_shipment`
SET `approval_status` = 'APPROVED'
WHERE `approval_status` = 'APPROVING'
  AND `shipment_status` = 'PENDING'
  AND `process_instance_id` IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `act_ru_execution` e WHERE e.`PROC_INST_ID_` = `fin_shipment`.`process_instance_id`
  );

UPDATE `fin_shipment` SET `approval_status` = 'REJECTED'
WHERE `approval_status` = 'APPROVING' AND `shipment_status` = 'REJECTED';

UPDATE `fin_shipment` SET `approval_status` = 'CANCELLED'
WHERE `approval_status` = 'APPROVING' AND `shipment_status` = 'CANCELLED';
