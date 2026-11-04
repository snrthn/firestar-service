-- ============================================================================
-- V61：fin_receipt / fin_invoice 补 process_instance_id 列。
-- 背景：回款/开票提交审批后由引擎回写 Flowable 流程实例 ID（ReceiptServiceImpl.submit /
--       InvoiceServiceImpl.submit 调 engine.start 后 setProcessInstanceId），并据此在详情页
--       判定「当前用户待审批」与「审批已通过」。此前实体/表均漏建该列（编译期即缺失）。
--       驳回/终结后保留该值便于排查，故可 NULL。
-- 依赖：V1（fin_receipt / fin_invoice 表已建）
-- ============================================================================

ALTER TABLE `fin_receipt`
  ADD COLUMN `process_instance_id` VARCHAR(64) NULL
  COMMENT 'Flowable 流程实例 ID（提交审批后写入，驳回/终结后保留便于排查）'
  AFTER `approval_status`;

ALTER TABLE `fin_invoice`
  ADD COLUMN `process_instance_id` VARCHAR(64) NULL
  COMMENT 'Flowable 流程实例 ID（提交审批后写入，驳回/终结后保留便于排查）'
  AFTER `invoice_status`;
