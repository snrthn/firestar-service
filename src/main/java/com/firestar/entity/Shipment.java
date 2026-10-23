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
@TableName(value = "fin_shipment")
public class Shipment extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 项目 ID → fin_project.id */
    private Long projectId;

    /** 批次号（规则 FH-项目编号-001） */
    private String batchNo;

    /** 发货时间 */
    private LocalDateTime shipmentDate;

    /** 发货金额 */
    private BigDecimal shipmentAmount;

    /** 已收金额 */
    private BigDecimal receivedAmount;

    /** 退货金额 */
    private BigDecimal returnedAmount;

    /** 发货状态（字典 shipment_status） */
    private String shipmentStatus;

    /** Flowable 流程实例 ID（提交审批后写入；驳回/终结后保留便于排查） */
    private String processInstanceId;

    /** 关联应收单 ID（1:1 反向引用） */
    private Long receivableId;

    /** 申请人ID */
    private Long applicantId;

    /** 发货人 ID */
    private Long shippedById;

    /** 确认人 ID */
    private Long confirmedById;
}
