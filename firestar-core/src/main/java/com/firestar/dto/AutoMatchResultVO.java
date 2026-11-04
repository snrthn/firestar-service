package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 自动核销结果（2026-10-25 阶段一，虎哥拍板 auto）。
 *
 * <p>算法：同项目内「未核销收款单 × 未回款应收单」金额贪心，日期早优先
 * （收款日期/应收日期升序，同日按 ID 升序）。逐收款单顺序吃应收单余额，
 * 每配上一对立即落 fin_write_off（method=AUTO）并滚数字，单事务。</p>
 */
@Getter
@Setter
public class AutoMatchResultVO {

    /** 配对笔数 */
    private Integer matchCount;

    /** 核销总金额（Σ pair.amount） */
    private BigDecimal totalAmount;

    /** 配对明细（按执行序） */
    private List<MatchedPairVO> pairs;
}
