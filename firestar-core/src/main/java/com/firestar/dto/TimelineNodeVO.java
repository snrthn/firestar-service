package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批时间线节点（2026-10-22 虎哥拍板：进度窗口不用 Tab，直接时间线）。
 *
 * <p>数据源 Flowable ACT_HI_TASKINST：一次查询即完整轨迹——已完成的（历史）、
 * 被驳回删除的（deleteReason 非空）、进行中的（endTime = null）都在同一张表，
 * 按 startTime 升序即时间线顺序。</p>
 */
@Getter
@Setter
public class TimelineNodeVO {

    /** 节点 ID（流程定义里的 taskDefinitionKey） */
    private String nodeId;

    /** 节点名称 */
    private String nodeName;

    /**
     * 节点结果：
     * ACTIVE    = 待审批（进行中，前端高亮）；
     * DONE      = 已通过（正常完成，可能有审批留言）；
     * REJECTED  = 已驳回/已跳过（任务被删除，deleteReason 非空）。
     */
    private String result;

    /**
     * 处理人展示名集合。
     * DONE / REJECTED：只放实际操作人（assignee，单人，虎哥确认点 2）；
     * ACTIVE：候选集合（会签多人 / ROLE 角色名，解析后可能多条）。
     */
    private List<String> handlerNames;

    /** 审批留言（Flowable 评论 ACT_HI_COMMENT，通过/驳回时填写；无则 null） */
    private String comment;

    /** 到达时间（任务创建） */
    private LocalDateTime startTime;

    /** 完成时间（ACTIVE 为 null） */
    private LocalDateTime endTime;
}
