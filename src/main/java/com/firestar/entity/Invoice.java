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
@TableName(value = "fin_invoice")
public class Invoice extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 项目 ID → fin_project.id */
    private Long projectId;

    /** 应收单 ID → fin_receivable.id */
    private Long receivableId;

    /** 单据方向：蓝字/红字（字典 doc_direction） */
    private String docDirection;

    /** 关联原发票 ID（红冲链） */
    private Long originalInvoiceId;

    /** 关联退货 ID */
    private Long returnId;

    /** 发票号码（文档漏项，方案补） */
    private String invoiceNo;

    /** 发票代码（文档漏项，方案补） */
    private String invoiceCode;

    /** 开票金额 */
    private BigDecimal invoiceAmount;

    /** 税率（文档漏项，方案补，如 0.1300=13%） */
    private BigDecimal taxRate;

    /** 税额（文档漏项，方案补） */
    private BigDecimal taxAmount;

    /** 开票日期 */
    private LocalDate invoiceDate;

    /** 发票文件路径（只存路径） */
    private String invoiceFilePath;

    /** 开票状态（字典 invoice_status） */
    private String invoiceStatus;

    /** Flowable 流程实例 ID（提交审批后写入，驳回/终结后保留便于排查） */
    private String processInstanceId;

    /** 发票类型（字典 invoice_type；V59 新增列：NORMAL 普通发票 / SPECIAL 增值税专用发票） */
    private String invoiceType;

    /** 申请人 ID */
    private Long applicantId;

    /** 开票员 ID */
    private Long clerkId;

    /** 下载人 ID */
    private Long downloaderId;

    /** 下载时间 */
    private LocalDateTime downloadedAt;
}
