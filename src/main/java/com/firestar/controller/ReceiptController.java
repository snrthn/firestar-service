package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.common.SecurityContextUtils;
import com.firestar.dto.AllocationItem;
import com.firestar.dto.ReceiptRequest;
import com.firestar.dto.ReceiptVO;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.service.ReceiptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 收款（回款）接口（前端 /api/receipt/**，路由不带 /api，同 ProjectController 约定）。
 *
 * <p>权限点 receipt:manage（ROLE_ADMIN 有保险网可直接调通）。</p>
 */
@RestController
@RequirePerm("receipt:manage")
@RequestMapping("/receipt")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    /** 分页列表（projectId/status 精确；字典翻译 + 用户名翻译） */
    @GetMapping("/page")
    public Result<IPage<ReceiptVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status) {
        return Result.success(receiptService.page(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                pageNum, pageSize, projectId, status));
    }

    /** 详情（含项目信息 + 字典翻译） */
    @GetMapping("/{id}")
    public Result<ReceiptVO> detail(@PathVariable Long id) {
        return Result.success(receiptService.detail(id,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin()));
    }

    /** 审批时间线（按 businessKey=收款单 ID 聚合） */
    @GetMapping("/{id}/timeline")
    public Result<List<TimelineNodeVO>> timeline(@PathVariable Long id) {
        return Result.success(receiptService.timeline(id));
    }

    /**
     * 事后核销分配（2026-10-25 虎哥拍板 A）：仅已收款态可调；全量替换 + 差额滚动，
     * 修复 V65 前老单「无 allocations 概念」与「预收未指定新单事后补核销」两类缺口。
     */
    @PostMapping("/{id}/allocate")
    public Result<Void> allocate(@PathVariable Long id, @RequestBody List<AllocationItem> items) {
        receiptService.allocate(id, items);
        return Result.success();
    }

    /** 创建收款单草稿（不拉审批） */
    @PostMapping
    public Result<Long> create(@RequestBody ReceiptRequest req) {
        return Result.success(receiptService.create(req));
    }

    /** 编辑收款单（仅草稿态；仅本人/admin） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ReceiptRequest req) {
        receiptService.update(id, req,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin());
        return Result.success();
    }

    /** 提交审批：拉起 receipt_approval（ROLE_CASHIER） */
    @PostMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        receiptService.submit(id);
        return Result.success();
    }

    /** 申请人撤回（仅本人/admin） */
    @PostMapping("/{id}/withdraw")
    public Result<Void> withdraw(@PathVariable Long id) {
        receiptService.withdraw(id,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin());
        return Result.success();
    }

    /** 审批通过回调 */
    @PostMapping("/task/{taskId}/complete")
    public Result<Void> complete(@PathVariable String taskId,
                                @RequestBody(required = false) Map<String, Object> variables) {
        receiptService.completeTask(taskId, variables);
        return Result.success();
    }

    /** 审批驳回 */
    @PostMapping("/task/{taskId}/reject")
    public Result<Void> reject(@PathVariable String taskId,
                              @RequestParam String comment) {
        receiptService.rejectTask(taskId, comment);
        return Result.success();
    }
}
