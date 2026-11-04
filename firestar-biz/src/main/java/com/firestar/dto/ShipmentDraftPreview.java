package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 发货单草稿预览（AI 拟单，阶段二）。
 *
 * <p>由 {@code ShipmentService.buildDraftPreview} 产出，纯只读校验 + 预览，
 * <b>不落库、不占货物额度、不触发任何写逻辑</b>。前端据此渲染「待确认表单」卡片，
 * 用户确认后才调原 {@code POST /shipment}（原 {@code shipment:create} 权限 + 原 Flowable 审批）。</p>
 *
 * <p>校验规则与 {@code ShipmentServiceImpl.validateAndOccupy} 同口径（超发 / 超合同），
 * 但只计算不改写；{@code errors} 非空代表无法创建（前端置灰确认按钮），{@code warnings} 为软提示。</p>
 */
@Getter
@Setter
public class ShipmentDraftPreview {

    private Long projectId;
    private String projectNo;
    private String projectName;
    private String customerName;
    private BigDecimal contractAmount;

    private List<ShipmentDraftLine> lines = new ArrayList<>();
    private BigDecimal totalAmount;

    /** 阻断性错误：非空则不可创建（前端置灰「确认创建」） */
    private List<String> errors = new ArrayList<>();
    /** 软告警：如某货物申请后剩余不足 10% */
    private List<String> warnings = new ArrayList<>();

    @Getter
    @Setter
    public static class ShipmentDraftLine {
        private Long itemId;
        private String itemName;
        private String specModel;
        private String unit;
        private Integer quantity;
        private Integer shippedQty;
        private Integer remaining;
        private Integer shipmentQty;
        private BigDecimal unitPrice;
        private BigDecimal lineAmount;
    }
}
