package com.firestar.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Tab⑤ 履约与风控 载体（L1 部分，仅含非 Flowable 指标；
 * 审批时效类 E-01/E-02/E-03 依赖 Flowable 历史表，后续单独接入）。
 *
 * <p>口径见 docs/数据看板-指标体系设计.md §5：
 * <ul>
 *   <li>D-09 退货率 = Σ 已完成退货额 / Σ 已发货额（排除 REJECTED）</li>
 *   <li>E-04 在途超期批次数 = shipment_status='SHIPPED' 且 shipment_date 早于（今天 - 7 天）</li>
 *   <li>D-10 红冲率 = |Σ RED 金额（收/票/应收）| / Σ BLUE 金额（区间，整体红冲强度）</li>
 * </ul>
 */
@Data
public class FulfillmentVO {

    /** D-09 退货率 */
    private BigDecimal returnRate = BigDecimal.ZERO;

    /** 累计已完成退货额 */
    private BigDecimal returnAmountTotal = BigDecimal.ZERO;

    /** 已发货额合计（排除 REJECTED，用于退货率分母） */
    private BigDecimal shipmentAmountTotal = BigDecimal.ZERO;

    /** E-04 在途超期批次数（&gt;7 天未收货） */
    private Long inTransitOverdueCount = 0L;

    /** D-10 红冲率（收/票/应收整体） */
    private BigDecimal redRate = BigDecimal.ZERO;
}
