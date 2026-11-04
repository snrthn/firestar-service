-- ============================================================================
-- V57：fin_shipment 补 approval_status 列（与立项 approval_status 同思路）。
-- 背景：发货状态机 shipment_status 的 PENDING 既表示「审批中」也表示「审批通过待发货」，
--   两者状态值完全相同，前端无法据此区分「是否已审批通过」。虎哥 2026-10-24 要求
--   「发货审批通过后不再展示编辑入口」，需要一个明确信号。
-- 取值：APPROVING（审批中）/ APPROVED（已通过）/ REJECTED（已驳回）/ CANCELLED（已撤销）。
--   创建即提交审批 → 落 APPROVING；审批通过回写 APPROVED；驳回 REJECTED；撤回 CANCELLED。
-- NOT NULL DEFAULT 'APPROVING'：新单从提交起即审批中；存量单（已发货/已收货等）状态非 PENDING，
--   编辑入口本就隐藏，approval_status 取值不影响其可见性；存量 PENDING 单若已审批通过会被
--   误判为 APPROVING（仍可编辑），属可接受的历史数据误差，后续如有需要可数据修正。
-- ============================================================================

ALTER TABLE `fin_shipment`
  ADD COLUMN `approval_status` VARCHAR(32) NOT NULL DEFAULT 'APPROVING'
  COMMENT '审批状态（APPROVING/APPROVED/REJECTED/CANCELLED；区分 shipment_status=PENDING 的审批中 vs 已通过待发货）'
  AFTER `process_instance_id`;
