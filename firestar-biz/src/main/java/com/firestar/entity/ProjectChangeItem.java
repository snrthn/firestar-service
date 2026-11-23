package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 变更明细增量 diff 行（V220）。
 *
 * <p>一条变更单可带多条 diff 行，op_type 三选一：</p>
 * <ul>
 *   <li>ADD：item_id 为空，审批通过后插入一条新 fin_item；</li>
 *   <li>MODIFY：item_id 指向 fin_item.id，quantity/unit_price 为目标值；
 *       应用时仅改未发部分（已发数量对应的字段不动）；</li>
 *   <li>DELETE：item_id 指向 fin_item.id，应用层仅允许 shippedQty=0 的行删除。</li>
 * </ul>
 *
 * <p>为什么用 diff 而非快照重建：fin_item.shipped_qty 记录已发占用，
 * 删除重建会丢占用导致超发校验失效——增量 diff 是保护已发数据的唯一正确姿势。</p>
 */
@Getter
@Setter
@TableName(value = "fin_project_change_item", autoResultMap = true)
public class ProjectChangeItem extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 变更单 ID → fin_project_change.id */
    private Long changeId;

    /** 操作类型：ADD / MODIFY / DELETE */
    private String opType;

    /** 原明细行 ID（MODIFY/DELETE 必填；ADD 为空） */
    private Long itemId;

    /** 货物/服务名称（ADD/MODIFY 目标值） */
    private String itemName;

    /** 规格型号 */
    private String specModel;

    /** 单位 */
    private String unit;

    /** 目标数量（DELETE 忽略） */
    private Integer quantity;

    /** 目标单价（必填 >0） */
    private BigDecimal unitPrice;

    /** 关联货物主表 ID（0 = 服务行不进主表） */
    private Long goodsId;
}
