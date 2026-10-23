package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 发货申请明细行（创建请求用）。
 *
 * <p>超发硬卡死（决策#3）：{@code shipmentQty} 必须 ≤ fin_item 剩余可发量
 * （= quantity − Σ已发量），否则 Service 层抛异常拒绝。剩余量在 ShipmentService 实时计算。</p>
 */
@Getter
@Setter
public class ShipmentLineCreateReq {

    /** 货物明细 ID → fin_item.id */
    private Long itemId;

    /** 本次申请发货数量（硬卡超发：> 剩余可发量则拒绝提交） */
    private Integer shipmentQty;

    /** 行备注（可选） */
    private String remark;
}
