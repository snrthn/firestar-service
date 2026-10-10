package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "fin_item")
public class Item extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 项目 ID → fin_project.id */
    private Long projectId;

    /** 货物名称 */
    private String itemName;

    /** 规格型号 */
    private String specModel;

    /** 单位 */
    private String unit;

    /** 数量 */
    private Integer quantity;

    /** 单价 */
    private BigDecimal unitPrice;

    /** 合计金额 */
    private BigDecimal totalAmount;
}
