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
import com.firestar.dto.ReturnCreateReq;
import com.firestar.dto.ReturnVO;
import com.firestar.dto.ReturnWriteoffDetailVO;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.service.ReturnService;
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
 * 退货接口（前端 /api/return/**，路由不带 /api，同 ShipmentController 约定）。
 *
 * <p>接口文档 9. 退货 6 接口 + 审批回调 3 个：
 * GET /returns（列表）/ GET /returns/{id}（详情）/
 * POST /shipments/{shipmentId}/returns（发起）/ GET /returns/pending（待审批）/
 * GET /returns/{id}/writeoff-detail（冲减明细）/ PUT /returns/{id}/cancel（撤销）。</p>
 *
 * <p>三级审批通过后系统自动执行三冲减链（红字应收单/红字发票/退款单，按条件跳过），
 * 无需手动调用（ReturnServiceImpl.completeTask 末节点驱动）。</p>
 */
@RestController
@RequirePerm("return:manage")
@RequestMapping("/return")
public class ReturnController {

    private final ReturnService returnService;

    public ReturnController(ReturnService returnService) {
        this.returnService = returnService;
    }

    /**
     * 退货列表（分页 + 筛选；字典翻译 + 我的待办任务 ID）。
     *
     * <p>查询条件（2026-10-28 补齐，口径对齐核销台账）：projectNo/projectName 模糊（反查项目）、
     * returnNo 模糊、status(审批状态)/executeStatus(执行状态) 精确、dateFrom~dateTo 按
     * 申请时间(applied_at) 闭区间。全部可空，空即不限。</p>
     */
    @GetMapping("/page")
    public Result<IPage<ReturnVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String projectNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String returnNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String executeStatus,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        return Result.success(returnService.page(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                pageNum, pageSize, projectNo, projectName, returnNo, status, executeStatus, dateFrom, dateTo));
    }

    /** 待审批的退货列表（按角色过滤：当前用户有待办任务的退货单） */
    @GetMapping("/pending")
    public Result<IPage<ReturnVO>> pending(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return Result.success(returnService.pending(
                SecurityContextUtils.currentUsername(), pageNum, pageSize));
    }

    /** 详情（含明细行 + 审批时间线 + 冲减明细） */
    @GetMapping("/{id}")
    public Result<ReturnVO> detail(@PathVariable Long id) {
        return Result.success(returnService.detail(id,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin()));
    }

    /** 审批时间线（businessKey=退货单 ID，流程类型 return_approval） */
    @GetMapping("/{id}/timeline")
    public Result<List<TimelineNodeVO>> timeline(@PathVariable Long id) {
        return Result.success(returnService.timeline(id));
    }

    /** 退货冲减明细（红字应收/红字发票/退款单/反向核销记录） */
    @GetMapping("/{id}/writeoff-detail")
    public Result<ReturnWriteoffDetailVO> writeoffDetail(@PathVariable Long id) {
        return Result.success(returnService.writeoffDetail(id));
    }

    /** 发起退货申请（挂在发货单下；创建即三级审批，接口文档 9.） */
    @PostMapping("/shipments/{shipmentId}/returns")
    @RequirePerm("return:create")
    public Result<Long> create(@PathVariable Long shipmentId, @RequestBody ReturnCreateReq req) {
        return Result.success(returnService.create(shipmentId, req));
    }

    /** 撤销退货申请（仅审批中可撤；引擎三道闸：发起人身份 + 流程运行中 + 并发） */
    @PutMapping("/{id}/cancel")
    @RequirePerm("return:cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        returnService.cancel(id);
        return Result.success();
    }

    /** 审批通过回调（三级推进；末节点通过自动执行三冲减链） */
    @PostMapping("/task/{taskId}/complete")
    @RequirePerm("return:approve")
    public Result<Void> complete(@PathVariable String taskId,
                                @RequestBody(required = false) Map<String, Object> variables) {
        returnService.completeTask(taskId, variables);
        return Result.success();
    }

    /** 审批驳回（终结实例 + REJECTED + 通知申请人） */
    @PostMapping("/task/{taskId}/reject")
    @RequirePerm("return:reject")
    public Result<Void> reject(@PathVariable String taskId,
                              @RequestParam(required = false) String toNode,
                              @RequestParam String comment) {
        returnService.rejectTask(taskId, comment);
        return Result.success();
    }

    /** 导出列定义（任务2：与列表可见字段对齐，中文标题 → VO 属性名） */
    private static final List<ExcelExportUtil.Column> RETURN_EXPORT_COLUMNS = List.of(
            new ExcelExportUtil.Column("退货单号", "returnNo"),
            new ExcelExportUtil.Column("项目编号", "projectNo"),
            new ExcelExportUtil.Column("项目名称", "projectName"),
            new ExcelExportUtil.Column("发货批次", "shipmentBatchNo"),
            new ExcelExportUtil.Column("退货金额", "returnAmount"),
            new ExcelExportUtil.Column("退货原因", "returnReason"),
            new ExcelExportUtil.Column("审批状态", "approvalStatusName"),
            new ExcelExportUtil.Column("执行状态", "executeStatusName"),
            new ExcelExportUtil.Column("申请人", "applicantName"),
            new ExcelExportUtil.Column("申请时间", "appliedAt"),
            new ExcelExportUtil.Column("创建时间", "createdAt")
    );

    /**
     * 导出 Excel（任务2）：复用 page 查询拿全量（pageSize=MAX 取 records），
     * 带与列表相同的筛选条件；权限点 return:export。
     */
    @GetMapping("/export")
    @RequirePerm("return:export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String projectNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String returnNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String executeStatus,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) throws IOException {
        List<ReturnVO> list = returnService.page(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                1, Integer.MAX_VALUE, projectNo, projectName, returnNo, status, executeStatus, dateFrom, dateTo
        ).getRecords();
        byte[] xlsx = ExcelExportUtil.export(list, RETURN_EXPORT_COLUMNS);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"return_export.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }
}
