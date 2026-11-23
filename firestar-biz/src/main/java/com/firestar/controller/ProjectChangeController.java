package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.common.SecurityContextUtils;
import com.firestar.dto.ChangeCreateReq;
import com.firestar.dto.ChangeVO;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.service.ProjectChangeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 合同变更接口（前端 /api/change/**，路由不带 /api，同其他 Controller 约定）。
 *
 * <p>V220：立项详情「发起变更」→ POST /change/project/{projectId} 创建并发起
 * project_change 两级审批（主管→经理）→ 审批中心回调 /change/task/{taskId}/complete，
 * 末节点通过由 ProjectChangeServiceImpl 应用增量 diff。</p>
 *
 * <p>审批入口独立（不挂 /project 下），与退货/发货/收款/发票同范式——
 * 每个业务自己的 completeTask，前端审批中心按 processDefinitionKey 分发到对应 URL。</p>
 */
@RestController
@RequestMapping("/change")
public class ProjectChangeController {

    private final ProjectChangeService changeService;

    public ProjectChangeController(ProjectChangeService changeService) {
        this.changeService = changeService;
    }

    /** 发起变更（创建即拉两级审批；仅 APPROVED 项目、申请人本人或管理员） */
    @PostMapping("/project/{projectId}")
    @RequirePerm("project:change")
    public Result<Long> create(@PathVariable Long projectId, @RequestBody ChangeCreateReq req) {
        return Result.success(changeService.create(projectId, req));
    }

    /** 变更单分页（项目ID/审批状态筛选；非管理员只看自己发起的） */
    @GetMapping("/page")
    public Result<IPage<ChangeVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String approvalStatus) {
        return Result.success(changeService.page(pageNum, pageSize, projectId, approvalStatus,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin()));
    }

    /** 项目下的变更单列表（立项详情 Tab 用） */
    @GetMapping("/project/{projectId}/list")
    public Result<List<ChangeVO>> listByProject(@PathVariable Long projectId) {
        return Result.success(changeService.listByProject(projectId));
    }

    /** 详情（含明细 diff + 审批时间线） */
    @GetMapping("/{id}")
    public Result<ChangeVO> detail(@PathVariable Long id) {
        return Result.success(changeService.detail(id,
                SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin()));
    }

    /** 审批时间线（businessKey = 变更单 ID，流程类型 project_change） */
    @GetMapping("/{id}/timeline")
    public Result<List<TimelineNodeVO>> timeline(@PathVariable Long id) {
        return Result.success(changeService.timeline(id));
    }

    /** 申请人撤销（仅审批中） */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        changeService.cancel(id);
        return Result.success();
    }

    /** 审批通过回调（两节点推进；末节点通过应用增量 diff） */
    @PostMapping("/task/{taskId}/complete")
    public Result<Void> complete(@PathVariable String taskId,
                                 @RequestBody(required = false) Map<String, Object> variables) {
        changeService.completeTask(taskId, variables);
        return Result.success();
    }

    /** 审批驳回（终结实例 + REJECTED + 通知申请人） */
    @PostMapping("/task/{taskId}/reject")
    public Result<Void> reject(@PathVariable String taskId,
                               @RequestParam String comment) {
        changeService.rejectTask(taskId, comment);
        return Result.success();
    }
}
