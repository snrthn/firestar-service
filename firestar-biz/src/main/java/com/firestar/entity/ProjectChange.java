package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 立项合同变更单（V220）。
 *
 * <p>虎哥 2026-11-23 拍板：保留立项合同金额手填，但把「改金额 / 增减明细」收进一条
 * 独立的受控审批链（project_change，主管→经理）。审批期间项目主表与明细表不动，
 * 审批通过后由 ChangeServiceImpl.applyChange 以增量 diff 应用到项目。</p>
 *
 * <p>与 fin_project 的关系：一个项目可有多张变更单（版本链），但不改项目的
 * approvalStatus / contractStatus——立项状态与签约状态各管各的，变更只改金额与明细。</p>
 *
 * <p>字段三处对齐：DDL（V220）/ 本实体 / 建表方案.md。</p>
 */
@Getter
@Setter
@TableName(value = "fin_project_change", autoResultMap = true)
public class ProjectChange extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 变更单号 CHG-{yyyyMMdd}-{id 四位补齐} */
    private String changeNo;

    /** 项目 ID → fin_project.id */
    private Long projectId;

    /** 变更原因（必填，合规留痕） */
    private String changeReason;

    /** 变更前合同金额 */
    private BigDecimal oldContractAmount;

    /** 变更后合同金额（null = 本次不改金额，仅改明细） */
    private BigDecimal newContractAmount;

    /** 变更窗口 A/B：A=无下游单据自由改；B=已有单据受限 */
    private String windowType;

    /** 审批状态（字典 approval_status：DRAFT/APPROVING/APPROVED/REJECTED/CANCELLED） */
    private String approvalStatus;

    /** 执行状态：PENDING/EXECUTED/CANCELLED */
    private String executeStatus;

    /** Flowable 流程实例 ID */
    private String processInstanceId;

    /** 发起人 ID */
    private Long applicantId;

    /** 发起审批时间 */
    private LocalDateTime appliedAt;

    /** 变更应用时间 */
    private LocalDateTime executedAt;
}
