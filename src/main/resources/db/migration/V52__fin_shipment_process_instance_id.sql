-- ============================================================================
-- V52：fin_shipment 补 process_instance_id 列。
-- 背景：发货提交审批后由 P3 引擎回写 Flowable 流程实例 ID（ShipmentServiceImpl.create
--       调 engine.start 后 setProcessInstanceId），并据此在详情页判定「当前用户待审批」
--       与「审批已通过待发货」。此前实体/表均漏建该列，编译期即缺失（运行时必 NPE）。
-- 驳回/终结后保留该值便于排查，故可 NULL。
-- ============================================================================

ALTER TABLE `fin_shipment`
  ADD COLUMN `process_instance_id` VARCHAR(64) NULL
  COMMENT 'Flowable 流程实例 ID（提交审批后写入，驳回/终结后保留便于排查）'
  AFTER `shipment_status`;
