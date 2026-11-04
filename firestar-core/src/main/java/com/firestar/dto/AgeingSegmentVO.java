package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 账龄五段单行（标签 / 金额 / 占比）。
 */
@Getter
@Setter
public class AgeingSegmentVO {

    /** 段位标签：未到期 / 0-30天 / 31-60天 / 61-90天 / 90天以上 */
    private String label;

    /** 该段未回款金额 */
    private BigDecimal amount = BigDecimal.ZERO;

    /** 该段占比（金额 ÷ 总未回款） */
    private BigDecimal ratio = BigDecimal.ZERO;
}
