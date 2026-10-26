package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 货物明细视图对象（fin_item 行展示）。
 *
 * <p>用于立项详情/编辑回填（{@link ProjectVO#getItems()}），以及发货侧按 projectId 拉取
 * （{@code ItemController}）。与前端 {@code types/shipment.ts} 的 ItemVO 字段对齐。</p>
 */
@Getter
@Setter
public class ItemVO {

    private Long id;
    private Long projectId;
    /** 货物 ID → fin_goods.id（V71；0 = 未关联；看板按此聚合） */
    private Long goodsId;
    private String itemName;
    private String specModel;
    private String unit;
    private Integer quantity;
    /** 已发货累计（超发判定基准：quantity - shippedQty = 剩余可发） */
    private Integer shippedQty;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
}
