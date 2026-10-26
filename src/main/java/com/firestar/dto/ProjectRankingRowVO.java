package com.firestar.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Tab④ 项目与销售 单项目行载体（对应需求文档第 6 条全周期台账 + 三完成率）。
 *
 * <p>口径：发货/回款/开票/应收均按项目聚合（同 ProjectServiceImpl.amountsOf 的代数口径：
 * 红字取负）；三完成率为派生比值，分母为 0 时记为 0。</p>
 */
@Data
public class ProjectRankingRowVO {

    private Long projectId;
    private String projectNo;
    private String projectName;
    private String customerName;
    private String managerName;

    /** 合同额（F-01 该项目的部分） */
    private BigDecimal contractAmount = BigDecimal.ZERO;

    /** 已发货额（F-02 该项目） */
    private BigDecimal shipmentAmount = BigDecimal.ZERO;

    /** 累计回款额（单据口径，F-07 该项目累计） */
    private BigDecimal receiptAmount = BigDecimal.ZERO;

    /** 累计开票额（F-04 该项目累计） */
    private BigDecimal invoiceAmount = BigDecimal.ZERO;

    /** 应收发生额（F-03 该项目累计） */
    private BigDecimal receivableAmount = BigDecimal.ZERO;

    /** 发货完成率 = 已发货 / 合同额 */
    private BigDecimal shipRate = BigDecimal.ZERO;

    /** 开票完成率 = 已开票 / 应收发生额 */
    private BigDecimal invoiceRate = BigDecimal.ZERO;

    /** 回款完成率 = 累计回款 / 合同额 */
    private BigDecimal receiptRate = BigDecimal.ZERO;
}
