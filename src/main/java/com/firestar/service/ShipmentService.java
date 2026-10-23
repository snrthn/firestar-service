package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.ShipmentConfirmReceiveReq;
import com.firestar.dto.ShipmentCreateReq;
import com.firestar.dto.ShipmentVO;
import com.firestar.dto.TimelineNodeVO;

import java.util.List;
import java.util.Map;

/**
 * 发货业务逻辑（决策全冻结 2026-10-23）。
 *
 * <p>状态机：PENDING（待发货/审批中，提交即拉审批）→ SHIPPED（已发货）→
 * PARTIAL_RECEIVED / FULL_RECEIVED（客户已收货，决策#2 时点联动生成应收单）。</p>
 *
 * <p>操作人（申请人/发货人/确认人）一律由业务层内部取当前登录用户（对齐 ProjectService），
 * 不在 Controller 层传 userId。</p>
 */
public interface ShipmentService {

    /** 创建发货申请并立即发起 P3 审批（shipment_approval，决策#1）。返回发货单 ID */
    Long create(ShipmentCreateReq req);

    /** 标记已发货（审批通过后；shippedById = 当前操作人，决策#2 澄清发货人=操作人自身） */
    void markShipped(Long shipmentId);

    /** 标记客户收货（决策#6 必传证据；支持按行部分收货；联动生成应收单） */
    void confirmReceive(ShipmentConfirmReceiveReq req);

    /** 审批通过回调（P3 引擎 complete 后业务回写 + 通知引导发货，决策#2） */
    void completeTask(String taskId, Map<String, Object> variables);

    /** 审批驳回（单节点流程无退回目标，故终结流程实例 + 置 REJECTED + 通知申请人，决策#2/#6 同人逻辑） */
    void rejectTask(String taskId, String comment);

    /** 分页列表（projectNo/projectName 模糊、status 精确；字典翻译 + 用户名翻译） */
    IPage<ShipmentVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                           String projectNo, String projectName, String status);

    /** 详情（含明细行 + 关联应收单 + 字典翻译） */
    ShipmentVO detail(Long shipmentId);

    /** 审批时间线（按 businessKey=发货单 ID 聚合，含驳回历史） */
    List<TimelineNodeVO> timeline(Long shipmentId);
}
