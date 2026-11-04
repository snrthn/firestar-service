package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 退货冲减明细 VO（V67：接口文档 9. /api/returns/{id}/writeoff-detail）。
 *
 * <p>三级审批通过后系统自动执行的三冲减结果快照（按条件跳过）：</p>
 * <ol>
 *   <li>红字应收单（恒执行）：负应收凭证，originalReceivableId 指原应收单</li>
 *   <li>红字发票（条件：原应收单已开票 ≥ 退货金额）：冲减已开票</li>
 *   <li>红字收款单=退款单（条件：原应收单已回款 ≥ 退货金额）：receiptType=REFUND，
 *       其核销行即反向记录（RED 方向核销原应收单，originalWriteOffId 留痕）</li>
 * </ol>
 */
@Getter
@Setter
public class ReturnWriteoffDetailVO {

    /** 红字应收单 ID → fin_receivable.id（FROM_RETURN） */
    private Long redReceivableId;

    private String redReceivableNo;

    /** 原应收单号 */
    private String originalReceivableNo;

    /** 红字应收金额（正数存储，前端按方向展示负号） */
    private BigDecimal redReceivableAmount;

    /** 红字发票 ID（未开票则 null，步骤跳过） */
    private Long redInvoiceId;

    private String redInvoiceNo;

    /** 原发票单号 */
    private String originalInvoiceNo;

    /** 红字发票金额（未开票则 null） */
    private BigDecimal redInvoiceAmount;

    /** 红字收款单（退款单）ID（未回款则 null，步骤跳过） */
    private Long redReceiptId;

    private String redReceiptNo;

    /** 原收款单号 */
    private String originalReceiptNo;

    /** 退款金额（未回款则 null） */
    private BigDecimal refundAmount;

    /** 反核销/反向核销记录行号（WO- 前缀，退款单的核销行） */
    private List<String> reverseWriteOffNos = new ArrayList<>();

    /** 执行说明（哪几步执行/哪几步跳过及原因，供前端详情展示） */
    private List<String> executionNotes = new ArrayList<>();
}
