package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 立项货物明细行（方案2：立项带行）。
 *
 * <p>随 {@link ProjectRequest} 一起提交，create/update 时落 {@code fin_item}，
 * 审批通过后作为合同货物清单存在，发货时引用这些行做超发校验。</p>
 */
@Getter
@Setter
public class ProjectItemReq {

    /** 货物名称（必填） */
    private String itemName;

    /** 规格型号 */
    private String specModel;

    /** 单位 */
    private String unit;

    /** 合同数量（必填，>0） */
    private Integer quantity;

    /** 单价（BigDecimal 纪律：禁 double/float） */
    private BigDecimal unitPrice;
}
