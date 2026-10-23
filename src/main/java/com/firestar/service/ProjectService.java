package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.ApprovalHistoryVO;
import com.firestar.dto.ProjectRequest;
import com.firestar.dto.ProjectSignRequest;
import com.firestar.dto.ProjectVO;
import com.firestar.dto.TaskInfo;
import com.firestar.dto.TimelineNodeVO;
import java.util.List;

/**
 * 项目立项业务服务（Step 3 流程编排入口）。
 *
 * <p>职责：立项 CRUD（DRAFT 态）+ 提交审批（经 {@code WorkflowEngineService} 门面发起流程）。
 * 审批通过后的编号赋予 / 驳回在 Step 3 ③④ 落地；签约为审批通过后的独立动作（2026-10-22 虎哥拍板）。</p>
 */
public interface ProjectService {

    /** 保存立项草稿（approvalStatus = DRAFT；contractStatus 强制 UNSIGNED，不接受传入） */
    Long create(ProjectRequest req);

    /** 编辑草稿（仅 DRAFT / REJECTED 态可改） */
    void update(Long id, ProjectRequest req);

    /** 详情（含字典翻译） */
    ProjectVO detail(Long id);

    /**
     * 分页查询（projectNo / projectName 模糊、approvalStatus 精确；默认 1/10，size 上限 500）。
     *
     * <p>数据行级隔离（2026-10-22 虎哥）：非 ROLE_ADMIN 只能看到 applicantUsername = username
     * 的自己发起的立项（含草稿）；viewAll = true（ROLE_ADMIN）时无人员限制看全部。</p>
     */
    IPage<ProjectVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                          String projectNo, String projectName, String approvalStatus);

    /**
     * 审批中心「我发起的」分页（2026-10-22 虎哥：审批中心 Tab 化）。
     * 同 page 范式 + 数据行级隔离（非 ROLE_ADMIN 仅 applicantUsername = username），
     * 并排除 DRAFT（未发起流程不算发起）；viewAll = true（ROLE_ADMIN）时无人员限制。
     */
    IPage<ProjectVO> pageMine(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                              String projectNo, String projectName, String approvalStatus);

    /**
     * 提交审批：DRAFT → 发起流程实例 → APPROVING，回写 processInstanceId。
     *
     * <p>要求对应流程定义（process_code = project_initiate）已配置且 ENABLED、已部署。</p>
     */
    void submit(Long id);

    /**
     * 申请人撤回（2026-10-23 虎哥拍板方案 B）：审批流程未结束时，发起人可主动撤回立项申请。
     * 引擎层三道闸（发起人身份 / 流程运行中 / 并发谁先谁赢）不过即抛异常。
     * 撤回后状态置 CANCELLED（终态只读），并强制通知已审批过的人（方案 B 核心补偿，
     * 否则他们的审批动作被无声抹掉 = 审计断链）。
     */
    void withdraw(Long id);

    /** 查询某立项单据的完整审批时间线（ACT_HI_TASKINST 单源，进行中节点前端高亮） */
    List<TimelineNodeVO> listTimeline(Long id);

    /**
     * 签约（2026-10-22 虎哥拍板）：审批通过后的独立动作。
     * 前置：approvalStatus = APPROVED 且 contractStatus = UNSIGNED；终态不可撤销。
     * 可修正最终合同金额（建项填的是预估）+ 补录签约描述（非必填）。
     */
    void sign(Long id, ProjectSignRequest req);

    /** 完成指定任务（审批通过；variables 可带 comment，落 Flowable 评论供时间线展示） */
    void completeTask(String taskId, java.util.Map<String, Object> variables);

    /**
     * 驳回：把指定任务退回目标节点。
     *
     * @param comment 驳回理由（必填，前端弹窗校验 + 服务层双重把关；透传给申请人通知 + Flowable 评论）
     */
    void rejectTask(String taskId, String toNode, String comment);

    /**
     * 审批中心「我的待办」：返回当前登录用户的所有 active 任务（跨流程实例）。
     * 每条已 enrich 业务标题（bizTitle）与可驳回目标节点（rejectTargets）。
     */
    List<TaskInfo> myTasks(String username);

    /**
     * 审批中心「我审批的」分页（2026-10-22 虎哥：标准页面查询+表格+分页）。
     * 在 myTasks 全量（三线合并 + enrich）之上按 bizTitle/taskName 过滤后内存分页——
     * 待办量级小（百级以内），内存分页零风险，避免给引擎三线 or 查询上 listPage 的未验证路径。
     */
    IPage<TaskInfo> myTasksPage(String username, Integer pageNum, Integer pageSize,
                                String bizTitleKw, String taskNameKw);

    /**
     * 审批中心「我审批的」历史分页（2026-10-22 虎哥：看不到自己审批过的记录）。
     * 在引擎 taskCompletedBy 全量历史之上 enrich 业务标题，按 bizTitle/taskName 过滤后内存分页
     * （同 myTasksPage 范式：量级小、零引擎查询改造风险）。
     */
    IPage<ApprovalHistoryVO> myApprovedHistoryPage(String username, Integer pageNum, Integer pageSize,
                                                  String bizTitleKw, String taskNameKw);
}
