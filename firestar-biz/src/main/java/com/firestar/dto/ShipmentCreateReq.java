package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 发货申请创建请求。
 *
 * <p>提交即发起 P3 审批（shipment_approval，一步 ROLE_SALES_MANAGER 节点，决策#1），
 * 审批通过后才进入「待发货」。本申请仅落草稿 + 拉流程，不直接生成应收单
 * （应收单在「客户已收货」时点生成，决策#2）。</p>
 */
@Getter
@Setter
public class ShipmentCreateReq {

    /** 项目 ID → fin_project.id */
    private Long projectId;

    /** 发货明细行（至少一行；逐行硬卡超发与超合同金额，决策#3/#4） */
    private List<ShipmentLineCreateReq> lines;
}
