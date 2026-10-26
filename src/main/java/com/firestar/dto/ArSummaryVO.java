package com.firestar.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Tab② 应收与回款 摘要载体（L1 部分，不含 FIFO 账龄/DSO——留 L2 的 AgeingService）。
 *
 * <p>口径见 docs/数据看板-指标体系设计.md §5：
 * <ul>
 *   <li>B-01 应收余额 = Σ unreceived_amount</li>
 *   <li>B-03 逾期金额 = Σ unreceived_amount where receivable_date &lt; 今天；逾期率 = 逾期 / 应收余额</li>
 *   <li>D-01 回款率 = F-07（本期回款·单据口径） / F-03（本期应收发生额）</li>
 *   <li>F-07 / F-03 / F-08 均为"本期/期间"口径</li>
 * </ul>
 * 注：DSO、账龄五段、应收周转率等由 L2 的 AgeingService 计算后合并返回。
 */
@Data
public class ArSummaryVO {

    /** B-01 应收余额（未回款） */
    private BigDecimal arBalance = BigDecimal.ZERO;

    /** B-03 逾期金额 */
    private BigDecimal overdueAmount = BigDecimal.ZERO;

    /** 逾期率 = 逾期 / 应收余额（无余额时 0） */
    private BigDecimal overdueRate = BigDecimal.ZERO;

    /** F-07 本期回款额（单据口径） */
    private BigDecimal receiptPeriod = BigDecimal.ZERO;

    /** F-03 本期应收发生额 */
    private BigDecimal receivablePeriod = BigDecimal.ZERO;

    /** D-01 回款率 = F-07 / F-03（无发生额时 0） */
    private BigDecimal collectionRate = BigDecimal.ZERO;

    /** F-08 本期回款额（核销口径） */
    private BigDecimal writeOffPeriod = BigDecimal.ZERO;

    /** F-09 差异池 = F-07 - F-08 */
    private BigDecimal diffPool = BigDecimal.ZERO;
}
