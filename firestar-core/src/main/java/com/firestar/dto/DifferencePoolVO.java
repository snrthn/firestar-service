package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 差异池（2026-10-25 阶段一）：按项目分组的「未核销收款单 × 未回款应收单」配对视图。
 *
 * <p>自动核销只同项目内配对（跨项目错配是资金事故），所以池天然按项目分组。
 * receipts/receivables 各自为空表示该项目该侧已配平。</p>
 */
@Getter
@Setter
public class DifferencePoolVO {

    private Long projectId;

    private String projectNo;

    /** 未核销收款单（已收款 RECEIVED + 蓝字 + unwritten_off_amount &gt; 0） */
    private List<PoolItemVO> receipts;

    /** 未回款应收单（unreceived_amount &gt; 0） */
    private List<PoolItemVO> receivables;
}
