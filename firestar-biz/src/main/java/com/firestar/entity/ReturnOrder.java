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
@TableName(value = "fin_return_order", autoResultMap = true)
public class ReturnOrder extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 退货单号（业务键） */
    private String returnNo;

    /** 项目 ID → fin_project.id */
    private Long projectId;

    /** 关联发货 ID → fin_shipment.id */
    private Long shipmentId;

    /** 退货金额 */
    private BigDecimal returnAmount;

    /** 退货原因 */
    private String returnReason;

    /** 审批状态（字典 approval_status） */
    private String approvalStatus;

    /** Flowable 流程实例 ID */
    private String processInstanceId;

    /** 冲减执行状态（字典 return_execute_status） */
    private String executeStatus;

    /** 发起人 ID */
    private Long applicantId;

    /** 退货人 ID（=项目客户 customer_id，货权方；V86） */
    private Long returnerId;

    /** 发货人 ID（=关联发货单 shipped_by_id；V86） */
    private Long shipperId;

    /** 发起时间 */
    private LocalDateTime appliedAt;
}
