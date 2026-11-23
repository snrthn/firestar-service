package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 变更明细 diff 行请求（V220）。
 *
 * <p>opType 三选一：</p>
 * <ul>
 *   <li>ADD：itemId 为空，name/spec/unit/quantity/unitPrice 为新行值；</li>
 *   <li>MODIFY：itemId 必填，name/spec/unit/quantity/unitPrice 为目标值；</li>
 *   <li>DELETE：itemId 必填，其余字段忽略。</li>
 * </ul>
 *
 * <p>单价必填 >0（虎哥 2026-11-23 拍板项 1）：包括服务行（差额作为一行明细录入）。</p>
 */
@Getter
@Setter
public class ChangeItemReq {

    /** 操作类型：ADD / MODIFY / DELETE */
    private String opType;

    /** 原明细行 ID（MODIFY/DELETE 必填；ADD 为空） */
    private Long itemId;

    /** 货物/服务名称（ADD/MODIFY 必填） */
    private String itemName;

    /** 规格型号 */
    private String specModel;

    /** 单位 */
    private String unit;

    /** 目标数量（ADD/MODIFY 必填 >0） */
    private Integer quantity;

    /** 目标单价（ADD/MODIFY 必填 >0） */
    private BigDecimal unitPrice;

    /** 关联货物 ID（服务行传 0；不传则后端按名称解析，服务项名称由前端标记） */
    private Long goodsId;
}
