package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 应收单视图对象（含字典翻译）。
 *
 * <p>由发货「客户已收货」时点自动生成（决策#2），1 发货 = 1 应收单
 * （fin_receivable.shipment_id 唯一键）。后续回款核销、开票均以本单为载体。</p>
 */
@Getter
@Setter
public class ReceivableVO {

    private Long id;

    /** 应收单号（业务键，生成规则见 ReceivableService） */
    private String receivableNo;

    private Long projectId;

    /** 关联发货 ID → fin_shipment.id（1:1） */
    private Long shipmentId;

    /** 单据方向：蓝字/红字（字典 doc_direction） */
    private String docDirection;

    /** 单据方向中文名 */
    private String docDirectionName;

    /** 应收金额 */
    private BigDecimal receivableAmount;

    /** 已核销金额 */
    private BigDecimal writtenOffAmount;

    /** 未核销金额 */
    private BigDecimal unwrittenOffAmount;

    /** 已开票金额 */
    private BigDecimal invoicedAmount;

    /** 未开票金额 */
    private BigDecimal uninvoicedAmount;

    /** 应收日期 */
    private LocalDate receivableDate;

    /** 状态（字典 receivable_status） */
    private String status;

    /** 状态中文名 */
    private String statusName;

    /** 状态标签色 */
    private String statusStyle;

    /** 来源类型（字典 receivable_source：FROM_SHIPMENT/退货红冲） */
    private String sourceType;

    /** 来源类型中文名 */
    private String sourceTypeName;
}
