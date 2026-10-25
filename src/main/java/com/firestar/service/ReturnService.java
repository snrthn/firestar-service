package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.ReturnCreateReq;
import com.firestar.dto.ReturnVO;
import com.firestar.dto.ReturnWriteoffDetailVO;
import com.firestar.dto.TimelineNodeVO;

import java.util.List;
import java.util.Map;

/**
 * 退货服务（V67 阶段二，接口文档 9. 退货 6 接口 + 审批回调）。
 *
 * <p>流程：发起退货（按行退，创建即三级审批）→ 主管→财务→经理 →
 * 末节点通过自动执行三冲减链（红字应收单恒执行；红字发票如已开票；
 * 红字收款单=退款单如已收款）→ 台账可查。</p>
 */
public interface ReturnService {

    /** 退货列表（分页 + 项目/状态筛选；字典翻译 + 我的待办任务 ID） */
    IPage<ReturnVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                         String projectNo, String projectName, String status);

    /** 详情（含明细行 + 审批时间线 + 冲减明细；归属校验同 Shipment 三选一） */
    ReturnVO detail(Long id, String username, boolean isAdmin);

    /** 发起退货申请（POST /shipments/{shipmentId}/returns：创建即拉三级审批） */
    Long create(Long shipmentId, ReturnCreateReq req);

    /** 待审批的退货列表（按角色过滤：当前用户有待办任务的退货单） */
    IPage<ReturnVO> pending(String username, Integer pageNum, Integer pageSize);

    /** 退货冲减明细（红字应收/红字发票/退款单/反向核销记录） */
    ReturnWriteoffDetailVO writeoffDetail(Long id);

    /** 撤销退货申请（仅审批中可撤；引擎三道闸 + 通知已审批人） */
    void cancel(Long id);

    /** 审批通过回调（三级推进；末节点通过执行三冲减链） */
    void completeTask(String taskId, Map<String, Object> variables);

    /** 审批驳回（终结实例 + REJECTED + 通知申请人） */
    void rejectTask(String taskId, String comment);

    /** 审批时间线（businessKey=退货单 ID，流程类型 return_approval） */
    List<TimelineNodeVO> timeline(Long id);
}
