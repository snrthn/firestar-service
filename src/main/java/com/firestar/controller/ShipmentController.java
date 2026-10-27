package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.common.SecurityContextUtils;
import com.firestar.dto.ShipmentConfirmReceiveReq;
import com.firestar.dto.ShipmentCreateReq;
import com.firestar.dto.ShipmentVO;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.service.ShipmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 发货接口（前端 /api/shipment/**，路由不带 /api，同 ProjectController 约定）。
 *
 * <p>权限点 shipment:manage（ROLE_ADMIN 有保险网可直接调通；菜单与授权种子留待前端补）。</p>
 */
@RestController
@RequirePerm("shipment:manage")
@RequestMapping("/shipment")
public class ShipmentController {

    private final ShipmentService shipmentService;

    public ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    /** 分页列表（批次号/项目号/项目名模糊、status(发货状态)精确、approvalStatus(审批状态)精确、发货日期区间；字典翻译 + 用户名翻译）
     *  ⚠️ status = 发货生命周期状态(shipment_status)；approvalStatus = 审批状态(approval_status，含 APPROVED)。
     *     退货弹窗按 approvalStatus=APPROVED 查询，严禁把 APPROVED 当 status 传（APPROVED 不是发货状态值）。
     *  ⚠️ 2026-10-28 修正：此前只声明 projectNo 却由前端传 batchNo，批次号查询框是死的；
     *     且 ServiceImpl 把 projectNo 实现成了 .like(batch_no)，参数名与语义错位——
     *     现新增真正的 batchNo 参数，projectNo 回归「按项目编号模糊」语义。 */
    @GetMapping("/page")
    public Result<IPage<ShipmentVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String projectNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String approvalStatus,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        return Result.success(shipmentService.page(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                pageNum, pageSize, batchNo, projectNo, projectName, status, approvalStatus, dateFrom, dateTo));
    }

    /** 详情（含明细行 + 关联应收单 + 字典翻译） */
    @GetMapping("/{id}")
    public Result<ShipmentVO> detail(@PathVariable Long id) {
        return Result.success(shipmentService.detail(id,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin()));
    }

    /** 审批时间线（按 businessKey=发货单 ID 聚合，含驳回/重提历史；详情页内联展示） */
    @GetMapping("/{id}/timeline")
    public Result<List<TimelineNodeVO>> timeline(@PathVariable Long id) {
        return Result.success(shipmentService.timeline(id));
    }

    /** 创建发货申请并立即发起 P3 审批（决策#1） */
    @PostMapping
    public Result<Long> create(@RequestBody ShipmentCreateReq req) {
        return Result.success(shipmentService.create(req));
    }

    /** 编辑发货单（2026-10-23 虎哥要求补入口）：仅 PENDING（待发货）可改，项目不可更换 */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ShipmentCreateReq req) {
        shipmentService.update(id, req,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin());
        return Result.success();
    }

    /**
     * 申请人撤回（方案 B）：审批流程未结束时，发起人可主动撤回 → CANCELLED 终态，
     * 并退还发货申请占用的货物额度。仅发起人可撤（引擎把关，管理员不代撤）。
     */
    @PostMapping("/{id}/withdraw")
    public Result<Void> withdraw(@PathVariable Long id) {
        shipmentService.withdraw(id);
        return Result.success();
    }

    /** 提交审批（详情面板触发；仅草稿/驳回可提，起流程 + 置 APPROVING） */
    @PostMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        shipmentService.submit(id,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin());
        return Result.success();
    }

    /** 标记已发货（审批通过后；shippedById = 当前操作人，决策#2 澄清发货人=操作人自身） */
    @PostMapping("/{id}/ship")
    public Result<Void> markShipped(@PathVariable Long id) {
        shipmentService.markShipped(id,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin());
        return Result.success();
    }

    /** 标记客户收货（决策#6 必传证据；支持按行部分收货；联动生成应收单） */
    @PostMapping("/{id}/confirm-receive")
    public Result<Void> confirmReceive(@PathVariable Long id, @RequestBody ShipmentConfirmReceiveReq req) {
        shipmentService.confirmReceive(req,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin());
        return Result.success();
    }

    /** 审批通过回调（P3 引擎 complete 后业务回写 + 通知引导发货，决策#2） */
    @PostMapping("/task/{taskId}/complete")
    public Result<Void> complete(@PathVariable String taskId,
                                @RequestBody(required = false) Map<String, Object> variables) {
        shipmentService.completeTask(taskId, variables);
        return Result.success();
    }

    /** 审批驳回（单节点流程：终结实例 + 置 REJECTED + 通知申请人；toNode 单节点无需，保留以便审批中心统一调用） */
    @PostMapping("/task/{taskId}/reject")
    public Result<Void> reject(@PathVariable String taskId,
                              @RequestParam(required = false) String toNode,
                              @RequestParam String comment) {
        shipmentService.rejectTask(taskId, comment);
        return Result.success();
    }
}
