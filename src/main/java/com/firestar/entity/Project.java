package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 项目 —— 核心实体，所有业务的载体。
 *
 * 本类是阶段 6 的**样板实体**：其余实体的映射规范以本类为准。
 *
 * 【金额字段纪律】
 * 全部 BigDecimal，禁止 double/float。数据库侧 DECIMAL(18,2)。
 * 后续计算必须走 BigDecimal，禁止用 += 累加（会丢精度）。
 *
 * 【枚举字段纪律】
 * 状态类字段一律 String，**不写 Java enum**。
 * 原因：枚举值由 sys_dict_data 维护（拍板项），数据库里加一个枚举值
 * 不应该要求改代码+重新发版。实体用 String 承接，前端展示时按字典翻译。
 */
@Getter
@Setter
@TableName(value = "fin_project", autoResultMap = true)
public class Project extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 项目编号（业务键，生成规则待定） */
    private String projectNo;

    /** 项目名称 */
    private String projectName;

    /** 客户名称（V1 不拆客户表，见方案 §1.3） */
    private String customerName;

    /** 销售经理 ID → sys_user.id */
    private Long salesManagerId;

    /** 合同签约状态（字典 project_contract_status；V46 起由签约动作后置驱动，建项时强制 UNSIGNED） */
    private String contractStatus;

    /** 合同总金额 */
    private BigDecimal contractAmount;

    /** 签约描述/签约留言（签约时补录，非必填；V46） */
    private String contractDesc;

    /** 签约时间（V46） */
    private java.time.LocalDateTime contractSignedAt;

    /** 签约人 → sys_user.id（V46） */
    private Long contractSignedBy;

    /** 审批发起人用户名（V47：审批中心「我发起的」过滤；create/submit 双写保新数据） */
    private String applicantUsername;

    /** 审批状态（Flowable 流程驱动） */
    private String approvalStatus;

    /** Flowable 流程实例 ID */
    private String processInstanceId;

    /** 已收款金额（回款审批通过累加，V59 汇总列） */
    private BigDecimal collectedAmount;

    /** 已开票金额（开票审批通过累加，V59 汇总列） */
    private BigDecimal invoicedAmount;

    /** 应收金额（应收单生成时累加，V59 汇总列） */
    private BigDecimal receivableAmount;
}
