package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 变更明细 diff 行 VO（V220）。
 */
@Getter
@Setter
public class ChangeItemVO {

    private Long id;
    private Long changeId;

    /** 操作类型：ADD / MODIFY / DELETE */
    private String opType;

    /** 原明细行 ID */
    private Long itemId;

    /** 货物/服务名称 */
    private String itemName;

    private String specModel;

    private String unit;

    private Integer quantity;

    private BigDecimal unitPrice;

    /** 关联货物 ID（0 = 服务行） */
    private Long goodsId;
}
