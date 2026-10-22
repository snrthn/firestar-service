package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 立项签约请求（2026-10-22 虎哥拍板：签约 = 审批通过后的独立动作）。
 *
 * <p>前置条件由 Service 把关：approvalStatus = APPROVED 且 contractStatus = UNSIGNED；
 * 签约即终态（a 拍板：不可撤销），签约时间/签约人由服务端记录，调用方无权指定。</p>
 */
@Getter
@Setter
public class ProjectSignRequest {

    /** 最终合同金额（b 拍板：签约时可修正，建项填的是预估；null = 保持原值） */
    private BigDecimal contractAmount;

    /** 签约描述/签约留言（非必填，限 500 字） */
    private String contractDesc;
}
