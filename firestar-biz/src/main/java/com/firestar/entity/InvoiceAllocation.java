package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 发票核销明细（V65：发票 ↔ 应收单多对多，严格同构 V1 fin_write_off）。
 *
 * <p>由 V65__receipt_allocation_and_receivable_received.sql 自动生成风格，
 * 改表请走 Flyway V{n} 增量脚本然后同步本文件。三处对齐：DDL / 本实体 / 建表方案.md。</p>
 *
 * <p>状态字典 writeoff_status（V2 已种）：NORMAL 正常 / REVERSED 已反核销。
 * 反核销链 originalAllocationId：撤回时不删行，置 REVERSED 并回填原行 ID（V1 范式注释：
 * 「一层，不可删只能反核销」）。</p>
 */
@Getter
@Setter
// autoResultMap：ext（JSON）列的 JacksonTypeHandler 读写依赖它（2026-10-25 反核销原因落 ext）
@TableName(value = "fin_invoice_allocation", autoResultMap = true)
public class InvoiceAllocation extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 核销单号（业务键，WO-yyyyMMdd-NNN，同 generateReceiptNo 范式） */
    private String allocationNo;

    /** 发票 ID → fin_invoice.id */
    private Long invoiceId;

    /** 应收单 ID → fin_receivable.id */
    private Long receivableId;

    /** 本次核销（开票分配）金额 */
    private BigDecimal amount;

    /** 核销状态（字典 writeoff_status：NORMAL/REVERSED） */
    private String status;

    /** 关联原核销 ID（反核销链） */
    private Long originalAllocationId;
}
