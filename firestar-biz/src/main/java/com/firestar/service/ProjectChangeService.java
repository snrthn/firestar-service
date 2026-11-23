package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.ChangeCreateReq;
import com.firestar.dto.ChangeVO;
import com.firestar.dto.TimelineNodeVO;

import java.util.Map;

/**
 * 立项合同变更服务（V220）。
 *
 * <p>流程：立项详情发起变更（金额 / 明细增量 diff / 原因）→ 创建变更单即发起
 * project_change 两级审批（主管→经理）→ 末节点通过，applyChange 以增量 diff
 * 应用到项目（改金额 + 增删明细），账目重新自洽。</p>
 *
 * <p>设计纪律：变更不改项目的 approvalStatus / contractStatus（立项/签约状态各管各的），
 * 只改 contractAmount 与 fin_item 明细行。审批期间项目主表与明细表完全不动。</p>
 */
public interface ProjectChangeService {

    /** 发起变更（POST /project/{projectId}/changes；创建即拉两级审批） */
    Long create(Long projectId, ChangeCreateReq req);

    /** 变更单分页（按项目/状态/发起人筛选） */
    IPage<ChangeVO> page(Integer pageNum, Integer pageSize, Long projectId, String approvalStatus, String username, boolean viewAll);

    /** 详情（含明细 diff + 审批时间线） */
    ChangeVO detail(Long id, String username, boolean isAdmin);

    /** 项目下的变更单列表（立项详情 Tab 用，不分页） */
    java.util.List<ChangeVO> listByProject(Long projectId);

    /** 申请人撤销（仅审批中） */
    void cancel(Long id);

    /** 审批通过回调（两节点推进；末节点通过应用变更） */
    void completeTask(String taskId, Map<String, Object> variables);

    /** 审批驳回（终结实例 + REJECTED + 通知申请人） */
    void rejectTask(String taskId, String comment);

    /** 审批时间线（businessKey = 变更单 ID，流程类型 project_change） */
    java.util.List<TimelineNodeVO> timeline(Long id);
}
