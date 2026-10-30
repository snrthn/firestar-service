package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.common.SecurityContextUtils;
import com.firestar.common.ExcelExportUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.io.IOException;
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

import java.time.LocalDate;
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

    /**
     * 分页列表（行级隔离：非 admin 只看 createdBy=自己；字典翻译 + 用户名翻译）。
     *
     * <p>查询条件（2026-10-28 补齐，口径对齐核销台账）：projectId/status/receiptType/
     * docDirection 精确、receiptNo 模糊、projectName 模糊（反查项目）、
     * dateFrom~dateTo 按收款日期闭区间。全部可空。</p>
     */
    @GetMapping("/page")
    public Result<IPage<ReceiptVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String receiptNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String receiptType,
            @RequestParam(required = false) String docDirection,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        return Result.success(receiptService.page(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                pageNum, pageSize, projectId, status, receiptNo, projectName,
                receiptType, docDirection, dateFrom, dateTo));
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
    @RequirePerm("receipt:create")
    public Result<Long> create(@RequestBody ReceiptRequest req) {
        return Result.success(receiptService.create(req));
    }

    /** 编辑收款单（仅草稿态；仅本人/admin） */
    @PutMapping("/{id}")
    @RequirePerm({"receipt:edit", "receipt:create"})
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

    /** 导出列定义（任务2：与列表可见字段对齐，中文标题 → VO 属性名） */
    private static final List<ExcelExportUtil.Column> RECEIPT_EXPORT_COLUMNS = List.of(
            new ExcelExportUtil.Column("收款单号", "receiptNo"),
            new ExcelExportUtil.Column("项目编号", "projectNo"),
            new ExcelExportUtil.Column("项目名称", "projectName"),
            new ExcelExportUtil.Column("客户名称", "customerName"),
            new ExcelExportUtil.Column("收款金额", "receiptAmount"),
            new ExcelExportUtil.Column("款项类型", "receiptTypeName"),
            new ExcelExportUtil.Column("单据方向", "docDirectionName"),
            new ExcelExportUtil.Column("收款日期", "receiptDate"),
            new ExcelExportUtil.Column("审批状态", "approvalStatusName"),
            new ExcelExportUtil.Column("收款业务状态", "receiptStatusName"),
            new ExcelExportUtil.Column("申请人", "applicantName"),
            new ExcelExportUtil.Column("创建时间", "createdAt")
    );

    /**
     * 导出 Excel（任务2）：复用 page 查询拿全量（pageSize=MAX 取 records），
     * 带与列表相同的筛选条件；权限点 receipt:export。
     */
    @GetMapping("/export")
    @RequirePerm("receipt:export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String receiptNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String receiptType,
            @RequestParam(required = false) String docDirection,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) throws IOException {
        List<ReceiptVO> list = receiptService.page(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                1, Integer.MAX_VALUE, projectId, status, receiptNo, projectName,
                receiptType, docDirection, dateFrom, dateTo
        ).getRecords();
        byte[] xlsx = ExcelExportUtil.export(list, RECEIPT_EXPORT_COLUMNS);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"receipt_export.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }
}
