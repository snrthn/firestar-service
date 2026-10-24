package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 收款单创建/编辑请求。
 *
 * <p>创建即草稿（approvalStatus = PENDING_APPROVAL），不拉审批；提交时由 submit 拉起
 * receipt_approval 流程（一步 ROLE_CASHIER 节点，与立项/发货一致）。</p>
 *
 * <p>docDirection 默认 BLUE（蓝字），创建时未传则业务层兜底写入。</p>
 */
@Getter
@Setter
public class ReceiptRequest {

    /** 项目 ID → fin_project.id（必填，校验存在） */
    private Long projectId;

    /** 收款金额（唯一直属金额输入，创建时落库；审批期间不可被旁路修改） */
    private BigDecimal receiptAmount;

    /** 款项类型（字典 receipt_type） */
    private String receiptType;

    /** 收款日期 */
    private LocalDate receiptDate;

    /** 银行回单路径（只存路径，不存文件） */
    private String bankReceiptPath;

    /** 备注（可选） */
    private String remark;
}
