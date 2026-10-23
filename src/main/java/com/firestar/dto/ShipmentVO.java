package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发货单视图对象（含字典翻译 + 明细行 + 关联应收单 + 签收证据）。
 *
 * <p>金额/数量维度校验（决策#3 超发硬卡、决策#4 不超合同）在 ShipmentService 完成；
 * 本 VO 仅承载查询结果。</p>
 */
@Getter
@Setter
public class ShipmentVO {

    private Long id;

    private Long projectId;

    /** 项目编号（fin_project.project_no，批次号 FH-{projectNo}-NNN 取自此处） */
    private String projectNo;

    /** 项目名称 */
    private String projectName;

    /** 客户名称 */
    private String customerName;

    /** 批次号（规则 FH-项目编号-NNN，决策#5） */
    private String batchNo;

    /** 发货时间（标记已发货时写入） */
    private LocalDateTime shipmentDate;

    /** 发货金额（Σ 行金额） */
    private BigDecimal shipmentAmount;

    /** 已收金额（客户已收货累计） */
    private BigDecimal receivedAmount;

    /** 退货金额 */
    private BigDecimal returnedAmount;

    /** 发货状态（字典 shipment_status） */
    private String shipmentStatus;

    /** 发货状态中文名 */
    private String shipmentStatusName;

    /** 发货状态标签色 */
    private String shipmentStatusStyle;

    /** 关联应收单 ID（1:1，客户已收货生成后才有） */
    private Long receivableId;

    /** Flowable 流程实例 ID（提交审批后非空） */
    private String processInstanceId;

    /** 审批状态（APPROVING/APPROVED/REJECTED/CANCELLED；前端据此判断「审批通过」以隐藏编辑入口） */
    private String approvalStatus;

    /** 申请人 ID */
    private Long applicantId;

    /** 申请人展示名（nickname 兜底 username） */
    private String applicantName;

    /** 发货人 ID（标记已发货时写入 = 当前操作人，决策#2 澄清发货人=操作人自身） */
    private Long shippedById;

    /** 发货人展示名 */
    private String shippedByName;

    /** 确认人 ID（客户已收货时写入 = 内部销售代确认） */
    private Long confirmedById;

    /** 确认人展示名 */
    private String confirmedByName;

    private LocalDateTime createdAt;

    /** 明细行 */
    private List<ShipmentLineVO> lines;

    /** 关联应收单（客户已收货生成后才有） */
    private ReceivableVO receivable;

    /** 签收证据文件 ID 列表（决策#6，confirmedById 非空时有值；前端据此调文件接口拉详情） */
    private List<Long> proofFileIds;

    /** 当前登录用户在本次发货单流程实例上的待办任务 ID（详情页据此直接「通过/驳回」；无则 null） */
    private String myPendingTaskId;
}
