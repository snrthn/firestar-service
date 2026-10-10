package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "fin_receipt")
public class Receipt extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 收款单号（业务键） */
    private String receiptNo;

    /** 项目 ID → fin_project.id */
    private Long projectId;

    /** 单据方向：蓝字/红字（字典 doc_direction） */
    private String docDirection;

    /** 关联原收款单 ID（红冲链） */
    private Long originalReceiptId;

    /** 关联退货 ID */
    private Long returnId;

    /** 收款金额（后端审批通过时写入） */
    private BigDecimal receiptAmount;

    /** 已核销金额 */
    private BigDecimal writtenOffAmount;

    /** 未核销金额 */
    private BigDecimal unwrittenOffAmount;

    /** 收款日期 */
    private LocalDate receiptDate;

    /** 银行回单路径（只存路径，不存文件） */
    private String bankReceiptPath;

    /** 审批状态（字典 receipt_status） */
    private String approvalStatus;

    /** 款项类型（字典 receipt_type） */
    private String receiptType;
}
