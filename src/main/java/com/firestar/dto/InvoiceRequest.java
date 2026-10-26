package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 发票（开票）创建/编辑请求。
 *
 * <p>创建即草稿（approvalStatus = PENDING），不拉审批；提交时由 submit 拉起
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

    /**
     * 应收单 ID → fin_receivable.id（NOT NULL）。
     *
     * <p>2026-10-25 V65 双向拆分后语义调整为「主应收单」：单张开票时即目标应收单；
     * 多张拆分（合并开）时取 allocations 第一行对应的应收单，保持主表列非空。</p>
     */
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

    /**
     * 核销分配明细（2026-10-25 V65 双向拆分，虎哥拍板 B）。
     *
     * <p>非空时强制 Σ amount = invoiceAmount 且每行 ≤ 该应收单 uninvoicedAmount（防超开）；
     * 空列表兜底为「主应收单独享」（{@link #receivableId}，兼容旧前端请求体）。
     * 审批通过时按明细滚各应收单 invoiced/uninvoiced，撤回反核销回滚。</p>
     */
    private List<AllocationItem> allocations;

    /**
     * 单据方向（2026-10-25 红冲入口，虎哥拍板「正数+RED 方向」）。
     *
     * <p>BLUE=蓝字（默认，普通发票）；RED=红字（红冲单，必须配 originalInvoiceId，
     * 金额仍存正数，审批通过时对应收单/项目汇总做减法，与税务「销项负数」对应）。</p>
     */
    private String docDirection;

    /**
     * 红冲原单 ID（docDirection=RED 时必填）。
     *
     * <p>约束：原单必须存在且已审批通过（ISSUED）、同项目、金额 ≤ 原单金额（支持部分红冲）。
     * BLUE 单传了也忽略。</p>
     */
    private Long originalInvoiceId;
}
