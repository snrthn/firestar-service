package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「我审批的」历史记录视图（2026-10-22 虎哥：用户看不到自己审批过的记录，审批中心加历史 Tab）。
 *
 * <p>数据源 Flowable ACT_HI_TASKINST：taskCompletedBy(当前用户) + finished，
 * 按结束时间倒序。结果列语义与时间线一致（DELETE_REASON_ 非空 = 该节点被我驳回跳走）。</p>
 */
@Getter
@Setter
public class ApprovalHistoryVO {

    /** Flowable 任务 ID（历史任务实例 id = 原任务 id） */
    private String taskId;

    /** 任务名称（取自节点 name） */
    private String taskName;

    /** 节点 id（= Flowable taskDefinitionKey） */
    private String nodeId;

    /** 所属流程实例 ID */
    private String processInstanceId;

    /** 业务单据标识（= 流程实例 businessKey，如项目 ID），前端据此调进度接口 */
    private String bizKey;

    /** 业务标题（如项目名称），由业务层 enrich，引擎层不直接查业务表 */
    private String bizTitle;

    /**
     * 处理人展示名集合（实际操作人，后端解析昵称）：
     * assignee 优先，空则取审批留言 userId 兜底（claim 修复前的老数据）。
     */
    private List<String> handlerNames;

    /** 审批留言（通过/驳回时填写；无则 null） */
    private String comment;

    /**
     * 我的审批结果：
     * DONE     = 通过（任务正常完成，deleteReason 空）；
     * REJECTED = 驳回（任务被 moveActivityIdTo 删除，deleteReason 非空）。
     */
    private String result;

    /** 处理时间（任务结束时间） */
    private LocalDateTime endTime;
}
