package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    /** 客户名称（快照冗余，V68 起不再作为聚合键）—— 显示用，勿用于 GROUP BY 统计 */
    private String customerName;

    /**
     * 客户 ID → fin_customer.id（V68；0/null = 未关联）。
     *
     * <p>看板「按客户」维度的聚合键 —— 不要拿 customerName 做 GROUP BY，文本会重名/别名分裂。</p>
     */
    private Long customerId;

    private Long salesManagerId;

    /** 销售经理展示名（nickname，兜底 username；列表批量翻译，2026-10-22 虎哥要求不再裸显 ID） */
    private String salesManagerName;

    private String contractStatus;

    private BigDecimal contractAmount;

    /** 累计发货金额（Σ fin_shipment.shipment_amount，按项目聚合；发货流程 V50 起有数，需求「项目六件套」之一） */
    private BigDecimal shippedAmount;

    /**
     * 累计已收货金额（Σ fin_shipment.received_amount）。
     *
     * <p>⚠️ 2026-10-25 V65 起该口径废弃：fin_shipment.received_amount 全库无写入方
     * （唯一写点是发货单创建时置 0），恒为 0。页面「累计已收」请改读
     * {@link #collectedAmount}（Σ RECEIVED 收款单，实时聚合）。本字段暂时保留同值
     * 兼容旧前端，后续版本移除。</p>
     */
    private BigDecimal receivedAmount;

    /**
     * 累计已收金额（Σ 审批通过的收款单 receipt_amount，按项目实时聚合，V65）。
     *
     * <p>虎哥 2026-10-25 拍板 A「按回款口径走」：不再读 shipment.received_amount
     * （死口袋），也不再依赖冗余字段 fin_project.collected_amount（撤回不扣会虚高），
     * 详情/列表一页一次 GROUP 语义聚合，天然只算 RECEIVED 态。</p>
     */
    private BigDecimal collectedAmount;

    /** 累计开票金额（Σ 审批通过的发票 invoice_amount，按项目实时聚合，V65） */
    private BigDecimal invoicedAmount;

    /** 应收金额合计（Σ 有效应收单 receivable_amount，按项目聚合，V65） */
    private BigDecimal receivableAmount;

    /** 是否允许回款前开出发票（V77，票款勾稽开关；true=允许默认，false=强制票款勾稽） */
    private Boolean allowInvoiceBeforeReceipt;

    /** 累计退货金额（Σ fin_shipment.returned_amount） */
    private BigDecimal returnedAmount;

    /** 签约描述/签约留言（签约时补录，非必填；V46） */
    private String contractDesc;

    /** 签约时间（V46） */
    private LocalDateTime contractSignedAt;

    /** 签约人 ID（V46） */
    private Long contractSignedBy;

    /** 签约人展示名（nickname 兜底 username；V46） */
    private String contractSignedByName;

    /** 审批状态（字典 approval_status：DRAFT/PENDING_APPROVAL/APPROVING/REJECTED/APPROVED/CANCELLED） */
    private String approvalStatus;

    /** 审批状态中文名（字典翻译） */
    private String approvalStatusName;

    /** 货物明细列表（方案2：立项带行；详情/编辑回填用，列表页不填充以避 N+1） */
    private List<ItemVO> items;

    /** 审批状态标签色（字典 style：info/warning/danger/success，V45 补种；前端 el-tag type 直接用） */
    private String approvalStatusStyle;

    /** Flowable 流程实例 ID（提交审批后非空） */
    private String processInstanceId;

    /** 当前审批节点名（审批中时由 Flowable 当前活跃任务名填充；非审批中为空） */
    private String currentNodeName;

    private LocalDateTime createdAt;
}
