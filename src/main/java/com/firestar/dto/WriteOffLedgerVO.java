package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 核销台账行（2026-10-25 阶段一：fin_write_off + fin_invoice_allocation 双表统一视图）。
 *
 * <p>用友/金蝶「核销台账」范式：所有核销动作（手工/自动/反核销）按时间序一屏可查，
 * 不再分散在收款详情、发票详情里各自展示。两表同构（V65 起），合并时用
 * {@link #ledgerType} 区分来源，字段名对齐到一套 VO。</p>
 *
 * <p>口径对齐：bizId/bizNo = 主单（回款核销→收款单；开票核销→发票）；
 * receivableId/receivableNo = 对手方应收单；method 仅回款侧有意义
 * （MANUAL/AUTO，开票侧核销均由分配产生，恒 MANUAL）。</p>
 */
@Getter
@Setter
public class WriteOffLedgerVO {

    /** 台账行 ID（各明细表主键） */
    private Long id;

    /** 台账类型：RECEIPT=回款核销（fin_write_off）/ INVOICE=开票核销（fin_invoice_allocation） */
    private String ledgerType;

    /** 核销单号（WO-yyyyMMdd-NNN / IA-yyyyMMdd-NNN） */
    private String ledgerNo;

    /** 项目 ID / 项目编号（经应收单带出，核销明细表本身无项目列） */
    private Long projectId;

    private String projectNo;

    /** 主单 ID / 单号（收款单或发票） */
    private Long bizId;

    private String bizNo;

    /** 应收单 ID / 单号 */
    private Long receivableId;

    private String receivableNo;

    /** 本次核销金额 */
    private BigDecimal amount;

    /** 状态（字典 writeoff_status）：NORMAL 正常 / REVERSED 已反核销 */
    private String status;

    /** 核销方式（字典 writeoff_method）：MANUAL / AUTO（开票侧恒 MANUAL） */
    private String method;

    /** 核销日期（回款侧 write_off_date；开票侧无此列，回落 created_at 日期） */
    private LocalDate ledgerDate;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 创建人 ID（fillNames 翻译 createdByName 用；前端可忽略） */
    private Long createdBy;

    /** 创建人用户名 */
    private String createdByName;

    /** 反核销链：原核销行 ID（REVERSED 行回填自身，方便链路追溯） */
    private Long originalLedgerId;

    /** 反核销原因（REVERSED 行的 ext.reverseReason） */
    private String reverseReason;
}
