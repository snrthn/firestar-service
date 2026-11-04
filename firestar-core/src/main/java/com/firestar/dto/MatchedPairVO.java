package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 自动核销配对结果行（2026-10-25 阶段一）：一笔「收款单 × 应收单」的落账记录。
 */
@Getter
@Setter
public class MatchedPairVO {

    /** 落库的核销单号（WO-yyyyMMdd-NNN，method=AUTO） */
    private String writeOffNo;

    private Long receiptId;

    private String receiptNo;

    private Long receivableId;

    private String receivableNo;

    /** 本pair核销金额（= min(收款单剩余, 应收单剩余)） */
    private BigDecimal amount;
}
