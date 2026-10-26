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
 * 不在 Controller 层传 userId。单条接口（detail/update/markShipped/confirmReceive）额外收
 * username/isAdmin 做归属校验（2026-10-24 安全债修复）。</p>
 */
public interface ShipmentService {

    /** 创建发货申请并立即发起 P3 审批（shipment_approval，决策#1）。返回发货单 ID */
    Long create(ShipmentCreateReq req);

    /** 标记已发货（审批通过后；shippedById = 当前操作人，决策#2 澄清发货人=操作人自身）；
     *  归属校验：仅申请人本人或 ROLE_ADMIN（写类不放开审批人） */
    void markShipped(Long shipmentId, String username, boolean isAdmin);

    /** 标记客户收货（决策#6 必传证据；支持按行部分收货；联动生成应收单）；
     *  归属校验：仅申请人本人或 ROLE_ADMIN */
    void confirmReceive(ShipmentConfirmReceiveReq req, String username, boolean isAdmin);

    /** 审批通过回调（P3 引擎 complete 后业务回写 + 通知引导发货，决策#2） */
    void completeTask(String taskId, Map<String, Object> variables);

    /**
     * 申请人撤回（2026-10-23 虎哥拍板方案 B）：审批流程未结束时，发起人可主动撤回发货申请。
     * 引擎层三道闸（发起人身份 / 流程运行中 / 并发谁先谁赢）不过即抛异常。
     * 撤回后状态置 CANCELLED，并强制通知已审批过的人。
     */
    void withdraw(Long shipmentId);

    /** 提交审批（详情面板触发；仅草稿/驳回可提，起流程 + 置 APPROVING） */
    void submit(Long id, String username, boolean isAdmin);

    /**
     * 编辑发货申请（2026-10-23 虎哥：发货单需要编辑入口）。
     * 仅 PENDING 可编辑，项目不可更换；先退还旧明细占用额度再按新明细重新校验占用，
     * 避免「减量编辑」被自己旧占用误判超发。
     * 归属校验：仅申请人本人或 ROLE_ADMIN。
     */
    void update(Long id, ShipmentCreateReq req, String username, boolean isAdmin);

    /** 审批驳回（单节点流程无退回目标，故终结流程实例 + 置 REJECTED + 通知申请人，决策#2/#6 同人逻辑） */
    void rejectTask(String taskId, String comment);

    /** 分页列表（projectNo/projectName 模糊、status 精确；字典翻译 + 用户名翻译） */
    IPage<ShipmentVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                           String projectNo, String projectName, String status);

    /** 详情（含明细行 + 关联应收单 + 字典翻译）；可见性：申请人 / ROLE_ADMIN / 审批任务处理人 三选一 */
    ShipmentVO detail(Long shipmentId, String username, boolean isAdmin);

    /** 审批时间线（按 businessKey=发货单 ID 聚合，含驳回历史） */
    List<TimelineNodeVO> timeline(Long shipmentId);
}
