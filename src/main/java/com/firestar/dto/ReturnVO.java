package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 退货单 VO（V67：列表/详情共用，详情时 lines / timeline / writeoffDetail 回填）。
 */
@Getter
@Setter
public class ReturnVO {

    private Long id;

    private String returnNo;

    private Long projectId;

    private String projectNo;

    private String projectName;

    /** 关联发货单 ID → fin_shipment.id */
    private Long shipmentId;

    /** 发货批次号（翻译） */
    private String shipmentBatchNo;

    /** 退货金额 = Σ 行金额（服务端汇总） */
    private BigDecimal returnAmount;

    /** 退货原因 */
    private String returnReason;

    /** 审批状态（字典 approval_status） */
    private String approvalStatus;

    private String approvalStatusName;

    private String approvalStatusStyle;

    /** 冲减执行状态（字典 return_execute_status） */
    private String executeStatus;

    private String executeStatusName;

    private String executeStatusStyle;

    /** 当前审批节点名（审批中时由 Flowable 当前活跃任务名填充；非审批中为空） */
    private String currentNodeName;

    /** Flowable 流程实例 ID */
    private String processInstanceId;

    private Long applicantId;

    private String applicantName;

    private LocalDateTime appliedAt;

    private LocalDateTime createdAt;

    /** 明细行（detail 回填） */
    private List<ReturnLineVO> lines;

    /** 审批时间线（detail 回填，businessKey=退货单 ID，流程类型 return_approval） */
    private List<TimelineNodeVO> timeline;

    /** 三冲减明细（detail 回填，审批通过后非空） */
    private ReturnWriteoffDetailVO writeoffDetail;

    /** 我的待审批任务 ID（列表用：当前用户有此单的候选任务时返回，供审批中心/列表直接跳审批） */
    private String myPendingTaskId;

    /**
     * 当前用户能否撤销（列表用：审批中 ∧ 本人申请 时为 true）。
     *
     * <p>与 myPendingTaskId 同源范式——前端拿不到可靠的当前用户 ID（ProfileVO 只含 username），
     * 与其让前端猜、点了再被后端三道闸弹错，不如由后端按 currentUserId 直接算好。
     * 后端 cancel 的三道闸（发起人身份 + 流程运行中 + 并发）仍是唯一权威，此字段仅控按钮显隐。</p>
     */
    private Boolean canCancel;
}
