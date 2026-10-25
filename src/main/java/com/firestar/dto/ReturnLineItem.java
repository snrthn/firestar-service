package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 退货明细行请求（V67：按行退）。
 *
 * <p>行单价取发货行当时的单价（{@code unitPrice} 不接受传入，防改价追溯歧义）；
 * 行金额由服务端按 数量 × 发货行单价 计算。</p>
 */
@Getter
@Setter
public class ReturnLineItem {

    /** 发货明细行 ID → fin_shipment_line.id */
    private Long shipmentLineId;

    /** 退货数量（必须为正，且 ≤ 该发货行已收数量 - 已退数量） */
    private Integer returnQty;
}
