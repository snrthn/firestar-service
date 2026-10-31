package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * 发票「开票」请求（V95 发票流程重构）。
 *
 * <p>开票节点（ROLE_INVOICER，{@code invoice:issue}）在审批通过进入 SUBMITTED（待开票）后，
 * 由开票员实际开具并回填以下字段。与申请阶段严格分离：</p>
 * <ul>
 *   <li>invoiceNo：税务发票号，<b>必填、不可随机生成</b>，手填真实票号；</li>
 *   <li>invoiceCode：发票代码（选填，纸质票有）；</li>
 *   <li>invoiceDate：开票日期（必填，财务凭证关键字段）；</li>
 *   <li>attachmentFileIds：电子发票文件 ID（必填至少一张，开票产物）。</li>
 * </ul>
 */
@Getter
@Setter
public class InvoiceIssueRequest {

    /** 税务发票号码（必填，真实票号，不随机生成） */
    private String invoiceNo;

    /** 发票代码（选填） */
    private String invoiceCode;

    /** 开票日期（必填） */
    private LocalDate invoiceDate;

    /** 电子发票附件文件 ID 列表（必填至少一张） */
    private List<Long> attachmentFileIds;
}
