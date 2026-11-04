package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 退货明细行 VO（V67）。
 */
@Getter
@Setter
public class ReturnLineVO {

    private Long id;

    /** 发货明细行 ID → fin_shipment_line.id */
    private Long shipmentLineId;

    /** 货物 ID → fin_item.id */
    private Long itemId;

    /** 货物名称（翻译） */
    private String itemName;

    /** 退货数量 */
    private Integer returnQty;

    /** 单价（冗余发货行单价） */
    private BigDecimal unitPrice;

    /** 行金额 */
    private BigDecimal lineAmount;
}
