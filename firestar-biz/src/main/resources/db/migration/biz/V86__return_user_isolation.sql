-- V86：退货单用户级隔离（2026-10-30 虎哥拍板：退货人 OR 发货人 OR 申请人可见）
-- returner_id = 客户（customer_id，货权方，即退货人）；
-- shipper_id  = 关联发货单的 shipped_by_id（发货人）。

ALTER TABLE fin_return_order ADD COLUMN returner_id BIGINT NULL COMMENT '退货人ID（=项目客户 customer_id，货权方）';
ALTER TABLE fin_return_order ADD COLUMN shipper_id  BIGINT NULL COMMENT '发货人ID（=关联发货单 shipped_by_id）';

-- 存量回填（幂等）
UPDATE fin_return_order r
  LEFT JOIN fin_shipment s ON s.id = r.shipment_id
  LEFT JOIN fin_project p ON p.id = r.project_id
SET r.shipper_id  = s.shipped_by_id,
    r.returner_id = p.customer_id;
