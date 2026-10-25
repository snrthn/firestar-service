package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "fin_receivable")
public class Receivable extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 应收单号（业务键） */
    private String receivableNo;

    /** 项目 ID → fin_project.id */
    private Long projectId;

    /** 关联发货 ID → fin_shipment.id（1:1） */
    private Long shipmentId;

    /** 单据方向：蓝字/红字（字典 doc_direction） */
    private String docDirection;

    /** 关联原应收单 ID（红冲链，已拍板不改原单状态） */
    private Long originalReceivableId;

    /** 关联退货 ID → fin_return_order.id */
    private Long returnId;

    /** 应收金额 */
    private BigDecimal receivableAmount;

    /** 已核销金额 */
    private BigDecimal writtenOffAmount;

    /** 未核销金额 */
    private BigDecimal unwrittenOffAmount;

    /** 已开票金额 */
    private BigDecimal invoicedAmount;

    /** 未开票金额 */
    private BigDecimal uninvoicedAmount;

    /** 已回款金额（V65：收款核销审批通过时按 fin_write_off 明细累加） */
    private BigDecimal receivedAmount;

    /** 未回款金额（V65：receivableAmount - receivedAmount） */
    private BigDecimal unreceivedAmount;

    /** 应收日期 */
    private LocalDate receivableDate;

    /** 状态（字典 receivable_status） */
    private String status;

    /** 来源类型（字典 receivable_source：FROM_SHIPMENT/FROM_RETURN） */
    private String sourceType;
}
