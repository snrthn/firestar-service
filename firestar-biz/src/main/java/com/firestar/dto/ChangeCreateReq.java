package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 发起项目变更请求（V220）。
 *
 * <p>接口：{@code POST /project/{projectId}/changes}，创建即发起 project_change 两级审批。</p>
 *
 * <p>金额与明细可单独或同时变更：</p>
 * <ul>
 *   <li>newContractAmount 非空 → 本次改金额；为 null → 金额不变，仅应用明细 diff；</li>
 *   <li>items 可为空（仅改金额）；非空则按增量 diff 应用（ADD/MODIFY/DELETE）。</li>
 * </ul>
 *
 * <p>变更原因必填——这是把"随手改字段"变成"受控业务动作"的核心留痕。</p>
 */
@Getter
@Setter
public class ChangeCreateReq {

    /** 变更原因（必填，合规留痕） */
    private String changeReason;

    /** 变更后合同金额（null = 不改金额；非 null 时须 ≥ 明细合计，窗口 B 另有下限） */
    private BigDecimal newContractAmount;

    /** 明细增量 diff（可空；空 = 只改金额） */
    private List<ChangeItemReq> items;
}
