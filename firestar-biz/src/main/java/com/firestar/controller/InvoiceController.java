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
import com.firestar.dto.InvoiceIssueRequest;
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

import java.time.LocalDate;
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

    /**
     * 分页列表（行级隔离：非 admin 只看 applicantId=自己；字典翻译 + 用户名翻译）。
     *
     * <p>查询条件（2026-10-28 补齐，口径对齐核销台账）：projectId/receivableId/status/
     * invoiceType/docDirection 精确、invoiceNo 模糊、projectName 模糊（反查项目）、
     * dateFrom~dateTo 按开票日期闭区间。全部可空，空即不限。</p>
     */
    @GetMapping("/page")
    public Result<IPage<InvoiceVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long receivableId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String invoiceNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String invoiceType,
            @RequestParam(required = false) String docDirection,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        return Result.success(invoiceService.page(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                pageNum, pageSize, projectId, receivableId, status,
                invoiceNo, projectName, invoiceType, docDirection, dateFrom, dateTo));
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
    @RequirePerm("invoice:create")
    public Result<Long> create(@RequestBody InvoiceRequest req) {
        return Result.success(invoiceService.create(req));
    }

    /** 编辑发票（仅草稿态；仅本人/admin） */
    @PutMapping("/{id}")
    @RequirePerm("invoice:edit")
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

    /** 开票（独立节点）：审批通过进入 SUBMITTED 后，由开票员（invoice:issue）填报真实票号/代码/日期 + 上传电子票 */
    @PostMapping("/{id}/issue")
    @RequirePerm("invoice:issue")
    public Result<Void> issue(@PathVariable Long id, @RequestBody InvoiceIssueRequest req) {
        invoiceService.issue(id, req);
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

    /** 导出列定义（任务2：与列表可见字段对齐，中文标题 → VO 属性名） */
    private static final List<ExcelExportUtil.Column> INVOICE_EXPORT_COLUMNS = List.of(
            new ExcelExportUtil.Column("发票号码", "invoiceNo"),
            new ExcelExportUtil.Column("项目名称", "projectName"),
            new ExcelExportUtil.Column("关联应收单号", "receivableNo"),
            new ExcelExportUtil.Column("开票金额", "invoiceAmount"),
            new ExcelExportUtil.Column("税率", "taxRate"),
            new ExcelExportUtil.Column("税额", "taxAmount"),
            new ExcelExportUtil.Column("发票类型", "invoiceTypeName"),
            new ExcelExportUtil.Column("单据方向", "docDirectionName"),
            new ExcelExportUtil.Column("开票日期", "invoiceDate"),
            new ExcelExportUtil.Column("开票状态", "approvalStatusName"),
            new ExcelExportUtil.Column("申请人", "applicantName"),
            new ExcelExportUtil.Column("开票员", "clerkName"),
            new ExcelExportUtil.Column("创建时间", "createdAt")
    );

    /**
     * 导出 Excel（任务2）：复用 page 查询拿全量（pageSize=MAX 取 records），
     * 带与列表相同的筛选条件；权限点 invoice:export。
     */
    @GetMapping("/export")
    @RequirePerm("invoice:export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long receivableId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String invoiceNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String invoiceType,
            @RequestParam(required = false) String docDirection,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) throws IOException {
        List<InvoiceVO> list = invoiceService.page(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                1, Integer.MAX_VALUE, projectId, receivableId, status,
                invoiceNo, projectName, invoiceType, docDirection, dateFrom, dateTo
        ).getRecords();
        byte[] xlsx = ExcelExportUtil.export(list, INVOICE_EXPORT_COLUMNS);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ExcelExportUtil.contentDisposition("发票管理"))
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }
}
