package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 差异池条目（2026-10-25 阶段一）：一张「还没配平」的单据。
 *
 * <p>收款单侧取 unwritten_off_amount（收款金额 - ΣNORMAL 核销行）；
 * 应收单侧取 unreceived_amount。两者都 &gt; 0 才进池——
 * 差异池的意义就是「有钱没分、有账没收」的待办视图，配平即出池。</p>
 */
@Getter
@Setter
public class PoolItemVO {

    private Long id;

    /** 单号（收款单号 / 应收单号） */
    private String no;

    /** 单据全额 */
    private BigDecimal amount;

    /** 未核销余额（收款单=unwritten_off_amount；应收单=unreceived_amount） */
    private BigDecimal unmatchedAmount;

    /** 单据日期（收款日期 / 应收日期，自动核销「日期优先」的排序依据） */
    private LocalDate docDate;
}
