-- V85：应收单用户级隔离（2026-10-30 虎哥拍板：申请人 OR 发货人可见）
-- 蓝字应收（发货生成）取发货单的申请人/发货人；
-- 红字应收（退货生成）取退货单申请人，发货人留 NULL（退货无发货动作）。

ALTER TABLE fin_receivable ADD COLUMN applicant_id BIGINT NULL COMMENT '申请人ID（应收生成来源：发货单申请人 / 退货单申请人）';
ALTER TABLE fin_receivable ADD COLUMN shipper_id  BIGINT NULL COMMENT '发货人ID（蓝字应收取自发货单 shipped_by_id；红字退货应收无发货动作，留 NULL）';

-- 存量回填（幂等，可重复执行）
-- ① 蓝字应收：shipment_id 非空，JOIN 发货单带出申请人与发货人
UPDATE fin_receivable r
  JOIN fin_shipment s ON s.id = r.shipment_id
SET r.applicant_id = s.applicant_id,
    r.shipper_id  = s.shipped_by_id
WHERE r.shipment_id IS NOT NULL;

-- ② 红字应收：return_id 非空且 shipment_id 为 NULL，仅带申请人（退货单申请人）
UPDATE fin_receivable r
  JOIN fin_return_order ro ON ro.id = r.return_id
SET r.applicant_id = ro.applicant_id
WHERE r.return_id IS NOT NULL
  AND r.shipment_id IS NULL;
