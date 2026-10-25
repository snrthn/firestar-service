package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 收款单视图对象（含字典翻译 + 项目信息 + 申请人名）。
 *
 * <p>金额/状态字典翻译在 ReceiptServiceImpl 完成；本 VO 仅承载查询结果。</p>
 */
@Getter
@Setter
public class ReceiptVO {

    private Long id;

    /** 收款单号（业务键，生成规则 SK-yyyyMMdd-NNN） */
    private String receiptNo;

    private Long projectId;

    /** 项目编号 */
    private String projectNo;

    /** 项目名称 */
    private String projectName;

    /** 客户名称 */
    private String customerName;

    /** 收款金额 */
    private BigDecimal receiptAmount;

    /** 单据方向：蓝字/红字（字典 doc_direction） */
    private String docDirection;

    /** 单据方向中文名 */
    private String docDirectionName;

    /** 单据方向标签色 */
    private String docDirectionStyle;

    /** 红冲原单 ID（docDirection=RED 时非空，指向被冲销的蓝字收款单） */
    private Long originalReceiptId;

    /** 红冲原单号（detail 回填，展示「原单：SK-xxx」；list 不回填避免 N+1） */
    private String originalReceiptNo;

    /** 款项类型（字典 receipt_type） */
    private String receiptType;

    /** 款项类型中文名 */
    private String receiptTypeName;

    /** 款项类型标签色 */
    private String receiptTypeStyle;

    /** 收款日期 */
    private LocalDate receiptDate;

    /** 银行回单路径 */
    /**
     * 历史残留列：fin_receipt.bank_receipt_path（V1 baseline 的「只存路径」设计，
     * 系统无任何环节会产出该路径 —— 2026-10-25 虎哥拍板改真上传后此列废弃，不再写入也不再展示。
     */
    private String bankReceiptPath;

    /**
     * 附件文件 ID 列表（软关联，2026-10-25 V62）。
     *
     * <p>不落在业务表上，而是 {@code sys_file_storage.biz_type='receipt' + biz_id=本单 id}，
     * 与发货签收证据（V54 shipment_receive_proof）严格同构。前端据此调 GET /files/{id} 取
     * 文件名、调 GET /files/{id}/download 取签名 URL 下载。</p>
     */
    private List<Long> attachmentFileIds;

    /** 审批状态（字典 receipt_status：PENDING_APPROVAL/APPROVING/RECEIVED/REJECTED） */
    private String approvalStatus;

    /** 审批状态中文名 */
    private String approvalStatusName;

    /** 审批状态标签色 */
    private String approvalStatusStyle;

    /** Flowable 流程实例 ID（提交审批后非空） */
    private String processInstanceId;

    /** 申请人 ID（归属人 = LogicDeleteEntity.createdBy） */
    private Long applicantId;

    /** 申请人展示名（nickname 兜底 username） */
    private String applicantName;

    private LocalDateTime createdAt;

    /** 当前登录用户在流程实例上的待办任务 ID（详情页据此直接通过/驳回；无则 null） */
    private String myPendingTaskId;

    /**
     * 核销分配明细（2026-10-25 V65，仅 detail 接口回填，避免列表 N+1）。
     *
     * <p>来源：fin_write_off where receipt_id = 本单 and status = 'NORMAL'
     * （撤回产生的 REVERSED 行不透出）。空列表 = 预收未指定应收单。</p>
     */
    private List<AllocationVO> allocations;
}
