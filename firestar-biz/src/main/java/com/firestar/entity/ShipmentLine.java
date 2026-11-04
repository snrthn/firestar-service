package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "fin_shipment_line")
public class ShipmentLine extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 发货 ID → fin_shipment.id */
    private Long shipmentId;

    /** 货物明细 ID → fin_item.id */
    private Long itemId;

    /** 发货数量 */
    private Integer shipmentQty;

    /** 已收数量 */
    private Integer receivedQty;

    /** 退货数量 */
    private Integer returnedQty;

    /** 行状态（字典 shipment_line_status） */
    private String lineStatus;

    /** 单价 */
    private BigDecimal unitPrice;

    /** 行金额 */
    private BigDecimal lineAmount;
}
