package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 项目立项 / 编辑请求（V1 不拆客户表，customerName 直接存字符串，见 Project 实体注释）。
 *
 * <p>不含 projectNo —— 需求文档规定「项目立项审批通过后，才赋予项目编号」，
 * 编号由审批完成动作回填，提交方无权指定。</p>
 */
@Getter
@Setter
public class ProjectRequest {

    /** 项目名称（必填） */
    private String projectName;

    /** 客户名称（V1 不拆客户表） */
    private String customerName;

    /** 销售经理 ID → sys_user.id */
    private Long salesManagerId;

    /** 合同总金额（BigDecimal 纪律：禁 double/float） */
    private BigDecimal contractAmount;

    /**
     * 货物明细（方案2：立项带行）。
     * 随立项的草稿/提交一起保存，作为合同货物清单；发货流程按行做超发校验。
     * 可为空（草稿允许暂不定明细），但提交审批（submit）时后端强制至少一行。
     */
    private List<ProjectItemReq> items;
}
