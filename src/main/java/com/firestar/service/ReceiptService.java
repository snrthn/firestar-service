package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.ReceiptVO;
import com.firestar.dto.ReceiptRequest;
import com.firestar.dto.TimelineNodeVO;

import java.util.List;
import java.util.Map;

/**
 * 收款（回款）业务逻辑。
 *
 * <p>状态机（字典 receipt_status）：PENDING_APPROVAL（草稿）→ APPROVING（提交审批）→
 * RECEIVED（审批通过终态）/ REJECTED（驳回终态）。审批通过终态时累加
 * fin_project.collected_amount。</p>
 *
 * <p>操作人（归属人）一律由业务层内部取当前登录用户（对齐 ProjectService），
 * 单条接口额外收 username/isAdmin 做归属校验（2026-10-24 安全债修复）。</p>
 */
public interface ReceiptService {

    /** 创建收款单草稿（不拉审批）。返回收款单 ID */
    Long create(ReceiptRequest req);

    /** 提交审批：拉起 receipt_approval（一步 ROLE_CASHIER 节点），回写 APPROVING + 提交通知 */
    void submit(Long id);

    /** 编辑收款单（仅草稿态；仅本人/ROLE_ADMIN） */
    void update(Long id, ReceiptRequest req, String username, boolean isAdmin);

    /** 申请人撤回（流程未结束；仅本人/ROLE_ADMIN；调 engine.withdraw + 回写 + 通知） */
    void withdraw(Long id, String username, boolean isAdmin);

    /** 审批通过回调（末节点通过 → 置 RECEIVED + 累加 fin_project.collected_amount + 通知） */
    void completeTask(String taskId, Map<String, Object> variables);

    /** 审批驳回（回写 REJECTED + 通知） */
    void rejectTask(String taskId, String comment);

    /** 分页列表（行级隔离：非 admin 只看 createdBy=自己；字典翻译 + 用户名翻译） */
    IPage<ReceiptVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                           Long projectId, String status);

    /** 详情（可见性：申请人 / ROLE_ADMIN / 审批任务处理人 三选一） */
    ReceiptVO detail(Long id, String username, boolean isAdmin);

    /** 审批时间线（按 businessKey=收款单 ID 聚合） */
    List<TimelineNodeVO> timeline(Long id);
}
