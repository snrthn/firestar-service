package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

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

    /** 货物名称（业务展示名；V71 起由 goods_id 关联货物主表，本列保留为可直接阅读的名称） */
    private String itemName;

    /**
     * 货物 ID → fin_goods.id（V71；0 或 null 表示未关联）。
     *
     * <p>与 {@link #itemName} 的关系同 fin_project.customerId/customerName：新单据由
     * ProjectServiceImpl.persistItems 按 itemName upsert 维护 goods_id；
     * 存量由 V71 迁移按名称回填。看板统计按本列聚合，避免文本重名分裂。</p>
     */
    private Long goodsId;

    /** 规格型号 */
    private String specModel;

    /** 单位 */
    private String unit;

    /** 数量 */
    private Integer quantity;

    /**
     * 已发货累计数量（V49 新增）。
     * 超发硬卡实时判断：quantity - shippedQty = 剩余可发量（决策#3）；
     * 写时带 version 乐观锁，并发超发会抛 OptimisticLockException 回滚。
     */
    private Integer shippedQty;

    /** 单价 */
    private BigDecimal unitPrice;

    /** 合计金额 */
    private BigDecimal totalAmount;
}
