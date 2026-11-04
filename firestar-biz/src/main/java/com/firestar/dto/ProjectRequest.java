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

    /**
     * 客户名称（V68 起为快照）。
     *
     * <p>兼容旧客户端：允许只传名称不传 id。后端 resolveCustomer 会按名称 upsert 建客户
     * 并回填 customerId，所以纯文本输入的老链路依然走得通。</p>
     */
    private String customerName;

    /**
     * 客户 ID → fin_customer.id（V68，选填）。
     *
     * <p>与 customerName 的优先级：<b>传了 id 以 id 为准</b>（再把主表的名称快照下来，
     * 防止前端手贱改名字时被覆盖）；没传 id 但有名称 → 按名称查/建客户。</p>
     */
    private Long customerId;

    /** 销售经理 ID → sys_user.id */
    private Long salesManagerId;

    /** 合同总金额（BigDecimal 纪律：禁 double/float） */
    private BigDecimal contractAmount;

    /**
     * 是否允许回款前开出发票（V77，票款勾稽开关）。
     * true(默认) = 允许先开票后回款；false = 不允许，发票侧强制票款勾稽。
     * 立项为空时后端默认 true，保持既有宽松行为。
     */
    private Boolean allowInvoiceBeforeReceipt;

    /**
     * 货物明细（方案2：立项带行）。
     * 随立项的草稿/提交一起保存，作为合同货物清单；发货流程按行做超发校验。
     * 可为空（草稿允许暂不定明细），但提交审批（submit）时后端强制至少一行。
     */
    private List<ProjectItemReq> items;
}
