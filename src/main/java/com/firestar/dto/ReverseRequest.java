package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 反核销请求（2026-10-25 阶段一）。
 *
 * <p>V1 范式「一层不可删只能反核销」：原核销行不物理删，置 REVERSED 留痕 +
 * originalLedgerId 回填自身 + ext.reverseReason 记原因，同时回滚应收单数字
 * 并重算主单已核销口径。</p>
 */
@Getter
@Setter
public class ReverseRequest {

    /** 台账类型：RECEIPT=回款核销（fin_write_off）/ INVOICE=开票核销（fin_invoice_allocation） */
    private String ledgerType;

    /** 核销行 ID */
    private Long ledgerId;

    /** 反核销原因（必填；落 ext.reverseReason 留痕） */
    private String reason;
}
