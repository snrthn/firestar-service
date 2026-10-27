package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.InvoiceRequest;
import com.firestar.dto.InvoiceVO;
import com.firestar.dto.TimelineNodeVO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 发票（开票）业务逻辑。
 *
 * <p>状态机（字典 approval_status）：PENDING（草稿）→ APPROVING（提交审批）→
 * ISSUED（审批通过终态，开票员=当前用户）/ REJECTED（驳回终态）。审批通过终态时累加
 * fin_project.invoiced_amount。</p>
 *
 * <p>操作人（归属人 = applicantId）由业务层内部取当前登录用户，单条接口额外收
 * username/isAdmin 做归属校验（2026-10-24 安全债修复）。</p>
 */
public interface InvoiceService {

    /** 创建发票草稿（不拉审批，校验 receivableId 存在）。返回发票 ID */
    Long create(InvoiceRequest req);

    /** 提交审批：拉起 invoice_approval（一步 ROLE_INVOICER 节点），回写 APPROVING + 提交通知 */
    void submit(Long id);

    /** 编辑发票（仅 PENDING 态；仅本人/ROLE_ADMIN） */
    void update(Long id, InvoiceRequest req, String username, boolean isAdmin);

    /** 申请人撤回（流程未结束；仅本人/ROLE_ADMIN） */
    void withdraw(Long id, String username, boolean isAdmin);

    /** 审批通过回调（末节点通过 → 置 ISSUED + clerkId + 累加 fin_project.invoiced_amount + 通知） */
    void completeTask(String taskId, Map<String, Object> variables);

    /** 审批驳回（回写 REJECTED + 通知） */
    void rejectTask(String taskId, String comment);

    /**
     * 分页列表（行级隔离：非 admin 只看 applicantId=自己；字典翻译 + 用户名翻译）。
     *
     * <p>查询条件（2026-10-28 补齐）：projectId/receivableId/status/invoiceType/docDirection
     * 精确、invoiceNo 模糊、projectName 模糊（反查项目）、dateFrom~dateTo 按 invoice_date
     * 闭区间。全部可空，空即不限。</p>
     */
    IPage<InvoiceVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                           Long projectId, Long receivableId, String status, String invoiceNo,
                           String projectName, String invoiceType, String docDirection,
                           LocalDate dateFrom, LocalDate dateTo);

    /** 详情（可见性：申请人 / ROLE_ADMIN / 审批任务处理人 三选一） */
    InvoiceVO detail(Long id, String username, boolean isAdmin);

    /** 审批时间线（按 businessKey=发票 ID 聚合） */
    List<TimelineNodeVO> timeline(Long id);
}
