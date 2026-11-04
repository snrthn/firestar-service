package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 客户收货明细行（标记客户收货请求用）。
 *
 * <p>支持按行部分收货：{@code receivedQty} 必须 ≤ 该行已发货量（shipmentQty），
 * 且不得覆盖已收 + 已退部分。全部行收齐 → 发货单推进 FULL_RECEIVED；
 * 部分行收齐 → PARTIAL_RECEIVED。</p>
 */
@Getter
@Setter
public class ShipmentLineReceiveReq {

    /** 货物明细 ID → fin_item.id */
    private Long itemId;

    /** 本次客户收货数量（≤ 该行已发货量；支持部分收货） */
    private Integer receivedQty;
}
