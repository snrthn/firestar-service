package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tab② L2 账龄与周转 VO（FIFO 法）。
 *
 * <p>口径（docs/数据看板-指标体系设计.md §3/§6）：
 * <ul>
 *   <li>账龄五段用 FIFO 法：回款（NORMAL 核销，排除退款）按 write_off_date 升序 FIFO 冲抵
 *       最老应收单（receivable_date 升序队列），每单剩余按各自账龄落段；</li>
 *   <li>回款一律核销口径（读 fin_write_off），REFUND 反向核销不计回款流；</li>
 *   <li>DSO（D-03）= 期末应收余额 ÷ 期间应收发生额 × 期间天数；周转率（D-04）= 365 ÷ DSO；</li>
 *   <li>平均逾期天数 ADD（D-06）= Σ(逾期额×逾期天数) ÷ Σ逾期额；逾期 = 未回款且 receivable_date < 今天。</li>
 * </ul>
 */
@Getter
@Setter
public class AgeingVO {

    /** 账龄算法标识，固定 FIFO */
    private String method = "FIFO";

    /** 总未回款（FIFO 重算余额，≈ B-01 应收余额） */
    private BigDecimal totalUnreceived = BigDecimal.ZERO;

    /** 账龄五段：未到期 / 0-30 / 31-60 / 61-90 / 90+ 天 */
    private List<AgeingSegmentVO> segments;

    /** D-03 DSO（应收周转天数） */
    private BigDecimal dso = BigDecimal.ZERO;

    /** D-04 年化应收周转率 = 365 ÷ DSO */
    private BigDecimal turnover = BigDecimal.ZERO;

    /** B-03 逾期金额 */
    private BigDecimal overdueAmount = BigDecimal.ZERO;

    /** 逾期率 = 逾期金额 ÷ 总未回款 */
    private BigDecimal overdueRate = BigDecimal.ZERO;

    /** D-06 平均逾期天数（ADD，金额加权） */
    private BigDecimal add = BigDecimal.ZERO;

    /** 逾期笔数 */
    private Integer overdueCount = 0;
}
