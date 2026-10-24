package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.common.SecurityContextUtils;
import com.firestar.dto.InvoiceRequest;
import com.firestar.dto.InvoiceVO;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.service.InvoiceService;
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
 * 发票（开票）接口（前端 /api/invoice/**，路由不带 /api，同 ProjectController 约定）。
 *
 * <p>权限点 invoice:manage（ROLE_ADMIN 有保险网可直接调通）。</p>
 */
@RestController
@RequirePerm("invoice:manage")
@RequestMapping("/invoice")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    /** 分页列表（projectId/receivableId/status 精确；字典翻译 + 用户名翻译） */
    @GetMapping("/page")
    public Result<IPage<InvoiceVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long receivableId,
            @RequestParam(required = false) String status) {
        return Result.success(invoiceService.page(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                pageNum, pageSize, projectId, receivableId, status));
    }

    /** 详情（含项目/应收单信息 + 字典翻译） */
    @GetMapping("/{id}")
    public Result<InvoiceVO> detail(@PathVariable Long id) {
        return Result.success(invoiceService.detail(id,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin()));
    }

    /** 审批时间线（按 businessKey=发票 ID 聚合） */
    @GetMapping("/{id}/timeline")
    public Result<List<TimelineNodeVO>> timeline(@PathVariable Long id) {
        return Result.success(invoiceService.timeline(id));
    }

    /** 创建发票草稿（不拉审批） */
    @PostMapping
    public Result<Long> create(@RequestBody InvoiceRequest req) {
        return Result.success(invoiceService.create(req));
    }

    /** 编辑发票（仅草稿态；仅本人/admin） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody InvoiceRequest req) {
        invoiceService.update(id, req,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin());
        return Result.success();
    }

    /** 提交审批：拉起 invoice_approval（ROLE_INVOICER） */
    @PostMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        invoiceService.submit(id);
        return Result.success();
    }

    /** 申请人撤回（仅本人/admin） */
    @PostMapping("/{id}/withdraw")
    public Result<Void> withdraw(@PathVariable Long id) {
        invoiceService.withdraw(id,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin());
        return Result.success();
    }

    /** 审批通过回调 */
    @PostMapping("/task/{taskId}/complete")
    public Result<Void> complete(@PathVariable String taskId,
                                @RequestBody(required = false) Map<String, Object> variables) {
        invoiceService.completeTask(taskId, variables);
        return Result.success();
    }

    /** 审批驳回 */
    @PostMapping("/task/{taskId}/reject")
    public Result<Void> reject(@PathVariable String taskId,
                              @RequestParam String comment) {
        invoiceService.rejectTask(taskId, comment);
        return Result.success();
    }
}
