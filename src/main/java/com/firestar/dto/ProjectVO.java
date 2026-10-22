package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 项目视图对象（含字典翻译后的 approvalStatusName）。
 */
@Getter
@Setter
public class ProjectVO {

    private Long id;

    /** 项目编号（审批通过后才赋予，此前为空） */
    private String projectNo;

    private String projectName;

    private String customerName;

    private Long salesManagerId;

    /** 销售经理展示名（nickname，兜底 username；列表批量翻译，2026-10-22 虎哥要求不再裸显 ID） */
    private String salesManagerName;

    private String contractStatus;

    private BigDecimal contractAmount;

    /** 签约描述/签约留言（签约时补录，非必填；V46） */
    private String contractDesc;

    /** 签约时间（V46） */
    private LocalDateTime contractSignedAt;

    /** 签约人 ID（V46） */
    private Long contractSignedBy;

    /** 签约人展示名（nickname 兜底 username；V46） */
    private String contractSignedByName;

    /** 审批状态（字典 project_approval_status：DRAFT/PENDING_APPROVAL/APPROVING/REJECTED/APPROVED/CANCELLED） */
    private String approvalStatus;

    /** 审批状态中文名（字典翻译） */
    private String approvalStatusName;

    /** 审批状态标签色（字典 style：info/warning/danger/success，V45 补种；前端 el-tag type 直接用） */
    private String approvalStatusStyle;

    /** Flowable 流程实例 ID（提交审批后非空） */
    private String processInstanceId;

    private LocalDateTime createdAt;
}
