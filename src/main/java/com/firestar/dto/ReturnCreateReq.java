package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 发起退货申请请求（V67）。
 *
 * <p>接口路径 {@code POST /shipments/{shipmentId}/returns}（接口文档 9. 退货），
 * shipmentId 走路径参数；本请求体带明细行 + 原因。</p>
 *
 * <p>创建即发起三级审批（return_approval：主管→财务→经理，V67 种子），
 * 与 Shipment「提交即审批」同范式；末节点通过自动执行三冲减链。</p>
 */
@Getter
@Setter
public class ReturnCreateReq {

    /** 退货明细行（至少一行；逐行校验 ≤ 可退数量） */
    private List<ReturnLineItem> lines;

    /** 退货原因（单头级，行级不细分——MVP 按虎哥「先跑通主链」） */
    private String returnReason;
}
