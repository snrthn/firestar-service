package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 标记已发货请求（「三部曲」：选承运商 + 运单号 + 上传附件）。
 *
 * <p>强约束（与决策#6 签收证据对称）：
 * <ul>
 *   <li>{@code carrier} 不可为空——必须为字典 logistics_carrier 中 <b>启用态</b> 的 dict_value；</li>
 *   <li>{@code trackingNo} 不可为空，且全库唯一（防一个单号发两单）；</li>
 *   <li>{@code fileIds} 不可为空——至少上传一件发货附件（bizType=shipment_dispatch），留内部凭据。</li>
 * </ul>
 * 三者缺一，markShipped 直接拒绝推进 SHIPPED。
 */
@Getter
@Setter
public class ShipmentMarkShippedReq {

    /** 发货单 ID */
    private Long shipmentId;

    /** 承运商（字典 logistics_carrier 的 dict_value，如 SF） */
    private String carrier;

    /** 物流运单号（库内唯一） */
    private String trackingNo;

    /** 发货附件文件 ID 列表（bizType=shipment_dispatch，至少 1 件） */
    private List<Long> fileIds;
}
