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
import com.firestar.dto.ApprovalHistoryVO;
import com.firestar.dto.ProjectRequest;
import com.firestar.dto.ProjectSignRequest;
import com.firestar.dto.ProjectOptionVO;
import com.firestar.dto.ProjectVO;
import com.firestar.dto.TaskInfo;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.service.ProjectService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目立项接口（Step 3 流程编排入口，前端 /api/project/**）。
 *
 * <p>路由不带 /api（同 RoleController 约定）。权限点 project:manage——
 * 菜单与授权种子留待 ⑦ 前端审批中心一起补（避免菜单点了白屏）；
 * ROLE_ADMIN 有保险网可直接调通。</p>
 */
@RestController
@RequirePerm("project:manage")
@RequestMapping("/project")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 分页立项列表（默认 1/10，size 上限 500）。
     *
     * <p>查询条件（2026-10-28 补齐，口径对齐核销台账）：projectNo/projectName/customerName
     * 模糊、approvalStatus/contractStatus/salesManagerId 精确、dateFrom~dateTo 按创建时间
     * 闭区间。全部可空，空即不限。</p>
     *
     * <p>数据行级隔离（2026-10-22 虎哥）：非 ROLE_ADMIN 只能看到自己发起的（applicantUsername
     * = 当前用户，含草稿）；ROLE_ADMIN 无条件放行看全部。</p>
     */
    @GetMapping("/page")
    public Result<IPage<ProjectVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String projectNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String approvalStatus,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String contractStatus,
            @RequestParam(required = false) Long salesManagerId,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        return Result.success(projectService.page(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                pageNum, pageSize, projectNo, projectName, approvalStatus,
                customerName, contractStatus, salesManagerId, dateFrom, dateTo));
    }

    /**
     * 审批中心「我发起的」分页（2026-10-22 虎哥：审批中心 Tab 化）。
     * 服务端自动按当前登录用户过滤（ROLE_ADMIN 除外），排除 DRAFT；查询参数同 /page。
     */
    @GetMapping("/page/mine")
    public Result<IPage<ProjectVO>> pageMine(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String projectNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String approvalStatus) {
        return Result.success(projectService.pageMine(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                pageNum, pageSize, projectNo, projectName, approvalStatus));
    }

    /** 保存立项草稿（approvalStatus = DRAFT） */
    @PostMapping
    @RequirePerm("project:create")
    public Result<Long> create(@RequestBody ProjectRequest req) {
        return Result.success(projectService.create(req));
    }

    /** 详情（含字典翻译） */
    /**
     * 项目下拉选项（看板「按项目筛选」用，V80；仿 customerOptions）。
     * 返回所有未删除项目（id/projectNo/projectName），按项目编号升序。
     */
    @GetMapping("/options")
    public Result<List<ProjectOptionVO>> options() {
        return Result.success(projectService.projectOptions());
    }

    @GetMapping("/{id}")
    public Result<ProjectVO> detail(@PathVariable Long id) {
        return Result.success(projectService.detail(id,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin()));
    }

    /** 编辑草稿（仅 DRAFT / REJECTED 态可改） */
    @PutMapping("/{id}")
    @RequirePerm({"project:edit", "project:create"})
    public Result<Void> update(@PathVariable Long id, @RequestBody ProjectRequest req) {
        projectService.update(id, req,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin());
        return Result.success();
    }

    /** 提交审批：DRAFT → 发起流程 → APPROVING */
    @PostMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        projectService.submit(id);
        return Result.success();
    }

    /**
     * 申请人撤回（方案 B）：审批流程未结束时，发起人可主动撤回 → CANCELLED 终态。
     * 仅发起人可撤（引擎三道闸把关，管理员不代撤）；已审批过的人会收到撤回通知。
     */
    @PostMapping("/{id}/withdraw")
    public Result<Void> withdraw(@PathVariable Long id) {
        projectService.withdraw(id);
        return Result.success();
    }

    /** 审批进度：查询某立项单据的完整审批时间线（进行中节点前端高亮，2026-10-22 时间线化改造） */
    @GetMapping("/{id}/timeline")
    public Result<List<TimelineNodeVO>> timeline(@PathVariable Long id) {
        return Result.success(projectService.listTimeline(id));
    }

    /** 签约：审批通过后的独立动作（可修正最终合同金额 + 补录签约描述；终态不可撤销） */
    @PostMapping("/{id}/sign")
    @RequirePerm("project:sign")
    public Result<Void> sign(@PathVariable Long id, @RequestBody ProjectSignRequest req) {
        projectService.sign(id, req,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin());
        return Result.success();
    }

    /** 审批通过：完成指定任务（variables 可选，用于向下一节点传参） */
    @PostMapping("/task/{taskId}/complete")
    public Result<Void> complete(@PathVariable String taskId,
                                @RequestBody(required = false) Map<String, Object> variables) {
        projectService.completeTask(taskId, variables);
        return Result.success();
    }

    /** 驳回：把指定任务退回目标节点（toNode 为目标节点 id，如申请人节点；comment 驳回理由必填） */
    @PostMapping("/task/{taskId}/reject")
    public Result<Void> reject(@PathVariable String taskId,
                               @RequestParam String toNode,
                               @RequestParam(required = false) String comment) {
        projectService.rejectTask(taskId, toNode, comment);
        return Result.success();
    }

    /** 我的待办：当前登录用户跨流程实例的 active 任务（审批中心数据源） */
    @GetMapping("/tasks/mine")
    public Result<List<TaskInfo>> myTasks() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return Result.success(projectService.myTasks(username));
    }

    /**
     * 审批中心「我审批的」分页（2026-10-22 虎哥：标准页面查询+表格+分页）。
     * bizTitleKw 按业务标题模糊、taskNameKw 按节点名模糊；服务端取当前登录用户。
     */
    @GetMapping("/tasks/mine/page")
    public Result<IPage<TaskInfo>> myTasksPage(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String bizTitleKw,
            @RequestParam(required = false) String taskNameKw) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return Result.success(
                projectService.myTasksPage(username, pageNum, pageSize, bizTitleKw, taskNameKw));
    }

    /**
     * 审批中心「我审批的」历史分页（2026-10-22 虎哥：看不到自己审批过的记录）。
     * 引擎 taskCompletedBy 全量历史 + enrich；查询参数同 /tasks/mine/page。
     */
    @GetMapping("/approvals/history/page")
    public Result<IPage<ApprovalHistoryVO>> myApprovedHistoryPage(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String bizTitleKw,
            @RequestParam(required = false) String taskNameKw) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return Result.success(
                projectService.myApprovedHistoryPage(username, pageNum, pageSize, bizTitleKw, taskNameKw));
    }

    /** 导出列定义（任务2：与列表可见字段对齐，中文标题 → VO 属性名） */
    private static final List<ExcelExportUtil.Column> PROJECT_EXPORT_COLUMNS = List.of(
            new ExcelExportUtil.Column("项目编号", "projectNo"),
            new ExcelExportUtil.Column("项目名称", "projectName"),
            new ExcelExportUtil.Column("客户名称", "customerName"),
            new ExcelExportUtil.Column("销售经理", "salesManagerName"),
            new ExcelExportUtil.Column("合同状态", "contractStatus"),
            new ExcelExportUtil.Column("合同金额", "contractAmount"),
            new ExcelExportUtil.Column("累计发货", "shippedAmount"),
            new ExcelExportUtil.Column("累计已收", "collectedAmount"),
            new ExcelExportUtil.Column("累计开票", "invoicedAmount"),
            new ExcelExportUtil.Column("累计应收", "receivableAmount"),
            new ExcelExportUtil.Column("审批状态", "approvalStatusName"),
            new ExcelExportUtil.Column("签约人", "contractSignedByName"),
            new ExcelExportUtil.Column("创建时间", "createdAt")
    );

    /**
     * 导出 Excel（任务2）：复用 page 查询拿全量（pageSize=MAX 取 records），
     * 带与列表相同的筛选条件；权限点 project:export。
     */
    @GetMapping("/export")
    @RequirePerm("project:export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String projectNo,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String approvalStatus,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String contractStatus,
            @RequestParam(required = false) Long salesManagerId,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) throws IOException {
        List<ProjectVO> list = projectService.page(
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin(),
                1, Integer.MAX_VALUE, projectNo, projectName, approvalStatus,
                customerName, contractStatus, salesManagerId, dateFrom, dateTo
        ).getRecords();
        byte[] xlsx = ExcelExportUtil.export(list, PROJECT_EXPORT_COLUMNS);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"project_export.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }
}
