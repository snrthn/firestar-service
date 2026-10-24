package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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

    /** 款项类型（字典 receipt_type） */
    private String receiptType;

    /** 款项类型中文名 */
    private String receiptTypeName;

    /** 款项类型标签色 */
    private String receiptTypeStyle;

    /** 收款日期 */
    private LocalDate receiptDate;

    /** 银行回单路径 */
    private String bankReceiptPath;

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
}
