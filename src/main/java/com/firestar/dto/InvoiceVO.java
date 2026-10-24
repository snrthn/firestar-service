package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 发票（开票）视图对象（含字典翻译 + 项目/应收单信息 + 申请人名）。
 *
 * <p>金额/状态字典翻译在 InvoiceServiceImpl 完成；本 VO 仅承载查询结果。</p>
 */
@Getter
@Setter
public class InvoiceVO {

    private Long id;

    /** 发票号码 */
    private String invoiceNo;

    /** 发票代码 */
    private String invoiceCode;

    private Long projectId;

    /** 项目名称 */
    private String projectName;

    /** 关联应收单 ID */
    private Long receivableId;

    /** 关联应收单号 */
    private String receivableNo;

    /** 开票金额 */
    private BigDecimal invoiceAmount;

    /** 税率 */
    private BigDecimal taxRate;

    /** 税额 */
    private BigDecimal taxAmount;

    /** 发票类型（字典 invoice_type） */
    private String invoiceType;

    /** 发票类型中文名 */
    private String invoiceTypeName;

    /** 发票类型标签色 */
    private String invoiceTypeStyle;

    /** 单据方向 */
    private String docDirection;

    /** 单据方向中文名 */
    private String docDirectionName;

    /** 开票日期 */
    private LocalDate invoiceDate;

    /** 发票文件路径 */
    private String invoiceFilePath;

    /** 开票状态（字典 invoice_status：PENDING/APPROVING/ISSUED/REJECTED） */
    private String invoiceStatus;

    /** 开票状态中文名 */
    private String invoiceStatusName;

    /** 开票状态标签色 */
    private String invoiceStatusStyle;

    /** Flowable 流程实例 ID（提交审批后非空） */
    private String processInstanceId;

    /** 申请人 ID（归属人 = applicantId） */
    private Long applicantId;

    /** 申请人展示名 */
    private String applicantName;

    /** 开票员 ID（审批通过时置为当前用户） */
    private Long clerkId;

    /** 开票员展示名 */
    private String clerkName;

    private LocalDateTime createdAt;

    /** 当前登录用户在流程实例上的待办任务 ID（无则 null） */
    private String myPendingTaskId;
}
