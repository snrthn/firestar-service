package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.common.ExcelExportUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.io.IOException;
import java.util.List;
import com.firestar.dto.ReceivableVO;
import com.firestar.service.ReceivableService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 应收单接口（前端 /api/receivable/**，路由不带 /api，同 ProjectController 约定）。
 *
 * <p>权限点 receivable:view（ROLE_ADMIN 有保险网可直接调通）。
 * 仅提供「详情 / 列表」只读能力；生成逻辑由 ShipmentServiceImpl 在「客户已收货」时触发，
 * 写入逻辑在 ReceivableServiceImpl 内部，不在此暴露写接口。</p>
 */
@RestController
@RequirePerm("receivable:view")
@RequestMapping("/receivable")
public class ReceivableController {

    private final ReceivableService receivableService;

    public ReceivableController(ReceivableService receivableService) {
        this.receivableService = receivableService;
    }

    /**
     * 分页列表（字典翻译）。
     *
     * <p>查询条件（2026-10-28 补齐，口径对齐核销台账）：projectId/status/sourceType 精确、
     * receivableNo 模糊、projectName 模糊（反查项目）、dateFrom~dateTo 按应收日期闭区间。</p>
     */
    @GetMapping("/page")
    public Result<IPage<ReceivableVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String receivableNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        return Result.success(receivableService.page(
                pageNum, pageSize, projectId, status, receivableNo, projectName, sourceType, dateFrom, dateTo));
    }

    /** 详情（含字典翻译） */
    @GetMapping("/{id}")
    public Result<ReceivableVO> detail(@PathVariable Long id) {
        return Result.success(receivableService.detail(id));
    }

    /** 导出列定义（任务2：与列表可见字段对齐，中文标题 → VO 属性名） */
    private static final List<ExcelExportUtil.Column> RECEIVABLE_EXPORT_COLUMNS = List.of(
            new ExcelExportUtil.Column("应收单号", "receivableNo"),
            new ExcelExportUtil.Column("项目编号", "projectNo"),
            new ExcelExportUtil.Column("项目名称", "projectName"),
            new ExcelExportUtil.Column("单据方向", "docDirectionName"),
            new ExcelExportUtil.Column("应收金额", "receivableAmount"),
            new ExcelExportUtil.Column("已核销金额", "writtenOffAmount"),
            new ExcelExportUtil.Column("未核销金额", "unwrittenOffAmount"),
            new ExcelExportUtil.Column("已开票金额", "invoicedAmount"),
            new ExcelExportUtil.Column("已回款金额", "receivedAmount"),
            new ExcelExportUtil.Column("应收日期", "receivableDate"),
            new ExcelExportUtil.Column("状态", "statusName"),
            new ExcelExportUtil.Column("来源类型", "sourceTypeName")
    );

    /**
     * 导出 Excel（任务2）：复用 page 查询拿全量（pageSize=MAX 取 records），
     * 带与列表相同的筛选条件；权限点 receivable:export。
     */
    @GetMapping("/export")
    @RequirePerm("receivable:export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String receivableNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) throws IOException {
        List<ReceivableVO> list = receivableService.page(
                1, Integer.MAX_VALUE, projectId, status, receivableNo, projectName, sourceType, dateFrom, dateTo
        ).getRecords();
        byte[] xlsx = ExcelExportUtil.export(list, RECEIVABLE_EXPORT_COLUMNS);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"receivable_export.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }
}
