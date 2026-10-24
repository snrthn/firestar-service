package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
    /**
     * 历史残留列（2026-10-25 起不再使用）：原「发票文件路径」，真上传改造后弃用。
     *
     * <p>附件改走 {@code attachmentFileIds} 软关联；此字段保留仅为兼容旧请求体，
     * 业务层不再写入。</p>
     */
    private String invoiceFilePath;

    /**
     * 发票附件文件 ID 列表（软关联，2026-10-25 V62）。
     *
     * <p>前端先调 POST /files/upload（bizType='invoice'、新建时无 id 故 bizId 留空）拿到
     * fileId，随本请求提交；业务层在单据落库后统一把这批文件的 bizType/bizId 改写挂载。</p>
     */
    private List<Long> attachmentFileIds;

    /** 备注（可选） */
    private String remark;
}
