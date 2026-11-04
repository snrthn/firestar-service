package com.firestar.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Tab① 经营总览 KPI 载体。
 *
 * <p>口径契约见 docs/数据看板-指标体系设计.md §5：
 * <ul>
 *   <li>F-01 合同签约额：Σ fin_project.contract_amount（approval_status='APPROVED'）</li>
 *   <li>F-02 本期发货额：Σ fin_shipment.shipment_amount（区间、排除 REJECTED）</li>
 *   <li>F-03 本期应收发生额：Σ fin_receivable.receivable_amount（区间，BLUE-RED）</li>
 *   <li>F-04/F-06 本期开票额/税额：Σ fin_invoice（区间、ISSUED，BLUE-RED）</li>
 *   <li>F-07 本期回款额（单据口径）：Σ fin_receipt.receipt_amount（区间、RECEIVED，RED/REFUND 取负）</li>
 *   <li>F-08 本期回款额（核销口径）：Σ fin_write_off.amount（区间、NORMAL）</li>
 *   <li>F-09 差异池 = F-07 - F-08</li>
 *   <li>B-01 应收余额：Σ fin_receivable.unreceived_amount（V65 权威列）</li>
 *   <li>B-02 待开票余额：Σ fin_receivable.uninvoiced_amount</li>
 *   <li>B-03 逾期金额：Σ unreceived_amount where receivable_date &lt; 今天</li>
 * </ul>
 * 所有金额字段不允许为 null，统一以 ZERO 占位。
 */
@Data
public class OverviewVO {

    /** F-01 合同签约额（存量累计） */
    private BigDecimal contractAmount = BigDecimal.ZERO;

    /** F-02 本期发货额（流量） */
    private BigDecimal shipmentAmount = BigDecimal.ZERO;

    /** F-03 本期应收发生额（流量） */
    private BigDecimal receivableOccurred = BigDecimal.ZERO;

    /** F-04 本期开票额（含税，流量） */
    private BigDecimal invoiceAmount = BigDecimal.ZERO;

    /** F-06 本期税额（流量） */
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /** F-07 本期回款额——单据口径（流量） */
    private BigDecimal receiptAmount = BigDecimal.ZERO;

    /** F-08 本期回款额——核销口径（流量） */
    private BigDecimal writeOffAmount = BigDecimal.ZERO;

    /** F-09 收款未核销差异池 = F-07 - F-08 */
    private BigDecimal diffPool = BigDecimal.ZERO;

    /** B-01 应收余额（未回款，存量） */
    private BigDecimal arBalance = BigDecimal.ZERO;

    /** B-02 待开票余额（存量） */
    private BigDecimal uninvoicedBalance = BigDecimal.ZERO;

    /** B-03 逾期金额（存量） */
    private BigDecimal overdueAmount = BigDecimal.ZERO;

    /** 项目总数（有效） */
    private Long projectCount = 0L;

    /** 客户总数（ENABLED） */
    private Long customerCount = 0L;

    /** 货物总数（ENABLED，V71 主表） */
    private Long goodsCount = 0L;
}
