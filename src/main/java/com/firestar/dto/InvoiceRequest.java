package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 发票（开票）创建/编辑请求。
 *
 * <p>创建即草稿（invoiceStatus = PENDING），不拉审批；提交时由 submit 拉起
 * invoice_approval 流程（一步 ROLE_INVOICER 节点，与立项/发货一致）。</p>
 *
 * <p>receivableId 必填且校验存在（发票必须挂在应收单上）。applicantId（归属人）由业务层
 * 创建时置为当前用户 ID。</p>
 */
@Getter
@Setter
public class InvoiceRequest {

    /** 项目 ID → fin_project.id */
    private Long projectId;

    /** 应收单 ID → fin_receivable.id（NOT NULL，创建必须传且校验存在） */
    private Long receivableId;

    /** 开票金额 */
    private BigDecimal invoiceAmount;

    /** 税率（如 0.1300 = 13%） */
    private BigDecimal taxRate;

    /** 税额 */
    private BigDecimal taxAmount;

    /** 发票号码 */
    private String invoiceNo;

    /** 发票代码 */
    private String invoiceCode;

    /** 发票类型（字典 invoice_type：NORMAL 普通发票 / SPECIAL 增值税专用发票） */
    private String invoiceType;

    /** 开票日期 */
    private LocalDate invoiceDate;

    /** 发票文件路径（只存路径） */
    private String invoiceFilePath;

    /** 备注（可选） */
    private String remark;
}
