-- 发货超发硬卡（决策#3）所需的实时累计字段：fin_item.shipped_qty
-- 超发判断 = quantity - shippedQty（剩余可发量）；写时带 version 乐观锁防并发。
-- 三处对齐：DDL(本文件) / 实体(Item.shippedQty) / 建表方案.md（附录补记）。
ALTER TABLE `fin_item`
    ADD COLUMN `shipped_qty` INT NOT NULL DEFAULT 0
    COMMENT '已发货累计数量（超发硬卡实时判断：quantity - shippedQty = 剩余可发量；带 version 乐观锁防并发）'
    AFTER `quantity`;
