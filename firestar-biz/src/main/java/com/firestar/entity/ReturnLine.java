package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * 退货明细行（V67：按行退，严格同构 V1 fin_shipment_line）。
 *
 * <p>虎哥 2026-10-25 拍板「建 fin_return_line」：退哪些货、每行退几件、行单价多少，
 * 退货金额 = Σ(行数量 × 行单价) 由服务层汇总，单头不冗余。</p>
 *
 * <p>改表请走 Flyway V{n} 增量脚本然后同步本文件。三处对齐：DDL / 本实体 / 建表方案.md。</p>
 */
@Getter
@Setter
@TableName(value = "fin_return_line", autoResultMap = true)
public class ReturnLine extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 退货单 ID → fin_return_order.id */
    private Long returnId;

    /** 发货明细行 ID → fin_shipment_line.id */
    private Long shipmentLineId;

    /** 货物 ID → fin_item.id */
    private Long itemId;

    /** 退货数量 */
    private Integer returnQty;

    /** 单价（冗余发货行单价，防改价追溯歧义） */
    private BigDecimal unitPrice;

    /** 行金额 = 数量 × 单价 */
    private BigDecimal lineAmount;
}
