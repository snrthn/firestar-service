package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    /** 红冲原单 ID（docDirection=RED 时非空，指向被冲销的蓝字发票） */
    private Long originalInvoiceId;

    /** 红冲原单号（detail 回填，展示「原单：YS-xxx」；list 不回填避免 N+1） */
    private String originalInvoiceNo;

    /** 开票日期 */
    private LocalDate invoiceDate;

    /** 发票文件路径 */
    /**
     * 历史残留列：fin_invoice.invoice_file_path（V1 baseline 的「只存路径」设计，
     * 系统无任何环节会产出该路径 —— 2026-10-25 虎哥拍板改真上传后此列废弃，不再写入也不再展示。
     */
    private String invoiceFilePath;

    /**
     * 附件文件 ID 列表（软关联，2026-10-25 V62）。
     *
     * <p>不落在业务表上，而是 {@code sys_file_storage.biz_type='invoice' + biz_id=本单 id}，
     * 与发货签收证据（V54 shipment_receive_proof）严格同构。前端据此调 GET /files/{id} 取
     * 文件名、调 GET /files/{id}/download 取签名 URL 下载。</p>
     */
    private List<Long> attachmentFileIds;

    /** 开票状态（字典 approval_status：PENDING/APPROVING/ISSUED/REJECTED） */
    private String approvalStatus;

    /** 开票状态中文名 */
    private String approvalStatusName;

    /** 开票状态标签色 */
    private String approvalStatusStyle;

    /** 当前审批节点名（审批中时由 Flowable 当前活跃任务名填充；非审批中为空） */
    private String currentNodeName;

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

    /** 业务申请单号（创建即生成，系统唯一，区别于税务发票号 invoiceNo） */
    private String applyNo;

    /** 购方名称（发票抬头，快照自客户表） */
    private String buyerName;

    /** 购方纳税人识别号 */
    private String buyerTaxNo;

    /** 购方地址 */
    private String buyerAddress;

    /** 购方电话 */
    private String buyerPhone;

    /** 购方开户银行 */
    private String buyerBankName;

    /** 购方银行账号 */
    private String buyerBankAccount;

    private LocalDateTime createdAt;

    /** 当前登录用户在流程实例上的待办任务 ID（无则 null） */
    private String myPendingTaskId;

    /**
     * 核销分配明细（2026-10-25 V65，仅 detail 接口回填，避免列表 N+1）。
     *
     * <p>来源：fin_invoice_allocation where invoice_id = 本单 and status = 'NORMAL'
     * （撤回产生的 REVERSED 行不透出）。空列表兜底为主应收单独享
     * （{@link #receivableId}，兼容 V65 前的历史数据）。</p>
     */
    private List<AllocationVO> allocations;
}
