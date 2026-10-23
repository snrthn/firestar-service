package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 发货明细行视图对象（含字典翻译后的行状态名/标签色）。
 */
@Getter
@Setter
public class ShipmentLineVO {

    private Long id;

    private Long shipmentId;

    /** 货物明细 ID → fin_item.id */
    private Long itemId;

    /** 货物名称（来自 fin_item.item_name） */
    private String itemName;

    /** 规格型号（来自 fin_item.spec_model） */
    private String specModel;

    /** 单位（来自 fin_item.unit） */
    private String unit;

    /** 发货数量 */
    private Integer shipmentQty;

    /** 已收数量 */
    private Integer receivedQty;

    /** 退货数量 */
    private Integer returnedQty;

    /** 行状态（字典 shipment_line_status） */
    private String lineStatus;

    /** 行状态中文名（字典翻译） */
    private String lineStatusName;

    /** 行状态标签色（字典 style：info/warning/success/danger） */
    private String lineStatusStyle;

    /** 单价 */
    private BigDecimal unitPrice;

    /** 行金额 */
    private BigDecimal lineAmount;

    /** 剩余可收货量 = shipmentQty − receivedQty − returnedQty（前端部分收货预校验用） */
    private Integer remainingCanReceive;
}
