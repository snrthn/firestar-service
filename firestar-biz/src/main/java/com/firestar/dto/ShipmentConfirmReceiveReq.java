package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 标记客户收货请求（决策#2 应收单生成时点 + 决策#6 证据留痕）。
 *
 * <p>强约束：{@code proofFileIds} 不可为空——内部销售代客户确认收货必须上传
 * 签收证据（bizType=shipment_receive_proof，复用 FileController），缺失则拒绝
 * 推进 FULL_RECEIVED。审批通过后、客户已收货时点联动生成应收单
 * （金额 = Σ receivedQty × unitPrice）。</p>
 */
@Getter
@Setter
public class ShipmentConfirmReceiveReq {

    /** 发货单 ID */
    private Long shipmentId;

    /** 逐行客户收货数量（支持部分收货，receivedQty ≤ 该行已发货量） */
    private List<ShipmentLineReceiveReq> lines;

    /** 签收证据文件 ID 列表（bizType=shipment_receive_proof，决策#6 必传，缺失拒绝推进） */
    private List<Long> proofFileIds;
}
