package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 合同变更单 VO（V220：列表/详情共用；detail 时 items / timeline 回填）。
 */
@Getter
@Setter
public class ChangeVO {

    private Long id;
    private String changeNo;
    private Long projectId;
    private String projectNo;
    private String projectName;

    /** 变更原因 */
    private String changeReason;

    /** 变更前金额 */
    private BigDecimal oldContractAmount;

    /** 变更后金额（null = 仅改明细） */
    private BigDecimal newContractAmount;

    /** 变更窗口 A/B */
    private String windowType;

    /** 审批状态 */
    private String approvalStatus;

    private String approvalStatusName;

    /** 执行状态：PENDING/EXECUTED/CANCELLED */
    private String executeStatus;

    /** 当前审批节点名（审批中时由 Flowable 活跃任务填充） */
    private String currentNodeName;

    /** Flowable 流程实例 ID */
    private String processInstanceId;

    private Long applicantId;
    private String applicantName;
    private LocalDateTime appliedAt;
    private LocalDateTime executedAt;
    private LocalDateTime createdAt;

    /** 明细 diff 行（detail 回填） */
    private List<ChangeItemVO> items;

    /** 审批时间线（detail 回填，businessKey = 变更单 ID，流程类型 project_change） */
    private List<TimelineNodeVO> timeline;

    /** 当前用户是否为待审批人（列表/详情用，供审批按钮显隐） */
    private String myPendingTaskId;

    /** 当前用户能否撤销（审批中 ∧ 本人申请） */
    private Boolean canCancel;
}
