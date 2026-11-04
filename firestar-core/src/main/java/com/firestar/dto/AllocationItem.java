package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 核销分配项（收款单 / 发票 ↔ 应收单，2026-10-25 V65 双向拆分，虎哥拍板 B）。
 *
 * <p>双向语义：一张收款单/发票可拆多张应收单（合并收/开），
 * 一张应收单可被多张收款单/多张发票分期核销（1→N 与 N→1 并存）。</p>
 *
 * <p>校验规则（业务层强制）：① Σ amount = 主单总额（receiptAmount / invoiceAmount）；
 * ② 每行 amount ≤ 该应收单未核销余额（收款比 unreceived_amount，发票比 uninvoiced_amount）。</p>
 */
@Getter
@Setter
public class AllocationItem {

    /** 应收单 ID → fin_receivable.id */
    private Long receivableId;

    /** 本次分配金额 */
    private BigDecimal amount;
}
