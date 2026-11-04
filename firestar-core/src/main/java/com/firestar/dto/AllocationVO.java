package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 核销分配展示项（详情页「哪张应收单分到多少」，2026-10-25 V65）。
 *
 * <p>与 {@link AllocationItem} 的差别：带回 receivableNo 供前端直接展示，
 * 无需再拉一次应收单列表。</p>
 */
@Getter
@Setter
public class AllocationVO {

    private Long receivableId;

    /** 应收单号（业务键） */
    private String receivableNo;

    /** 本次分配金额 */
    private BigDecimal amount;
}
