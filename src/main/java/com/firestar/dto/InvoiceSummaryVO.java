package com.firestar.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Tab③ 开票与票税 摘要载体。
 *
 * <p>口径见 docs/数据看板-指标体系设计.md §5：
 * <ul>
 *   <li>F-04 本期开票额（含税）= Σ invoice_amount（区间、ISSUED，BLUE-RED）</li>
 *   <li>F-05 本期不含税开票额 = Σ (invoice_amount - tax_amount)</li>
 *   <li>F-06 本期税额 = Σ tax_amount</li>
 *   <li>B-02 待开票余额 = Σ uninvoiced_amount</li>
 *   <li>D-02 开票率 = Σ 已开票额（累计 ISSUED） / Σ 应收发生额（累计）</li>
 *   <li>D-10 红字发票率 = |Σ RED 发票额| / Σ BLUE 发票额（按方向代数，区间）</li>
 * </ul>
 */
@Data
public class InvoiceSummaryVO {

    /** F-04 本期开票额（含税） */
    private BigDecimal invoiceAmount = BigDecimal.ZERO;

    /** F-06 本期税额 */
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /** F-05 本期不含税开票额 = 开票额 - 税额 */
    private BigDecimal invoiceAmountNet = BigDecimal.ZERO;

    /** B-02 待开票余额（存量） */
    private BigDecimal uninvoicedBalance = BigDecimal.ZERO;

    /** D-02 开票率（累计） */
    private BigDecimal invoiceRate = BigDecimal.ZERO;

    /** D-10 红字发票率（区间） */
    private BigDecimal redInvoiceRate = BigDecimal.ZERO;

    /** 本期回款额（单据口径，用于"票款对比"图） */
    private BigDecimal receiptAmount = BigDecimal.ZERO;
}
