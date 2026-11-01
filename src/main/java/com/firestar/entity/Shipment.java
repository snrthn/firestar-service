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

    /** 批次编号（规则 FH-项目编号-001） */
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

    /** 审批状态（APPROVING/APPROVED/REJECTED/CANCELLED；区分 shipment_status=PENDING 的审批中 vs 已通过待发货） */
    private String approvalStatus;

    /** 关联应收单 ID（1:1 反向引用） */
    private Long receivableId;

    /** 申请人ID */
    private Long applicantId;

    /** 发货人 ID */
    private Long shippedById;

    /** 物流运单号（标记已发货必填，库内唯一；审计可去承运商核验真发没发） */
    private String trackingNo;

    /** 承运商（字典 logistics_carrier 的 dict_value，如 SF；标记已发货必选） */
    private String carrier;

    /** 确认人 ID */
    private Long confirmedById;
}
