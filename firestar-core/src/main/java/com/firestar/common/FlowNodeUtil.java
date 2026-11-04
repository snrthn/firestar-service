package com.firestar.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.entity.ProcessDef;
import com.firestar.mapper.ProcessDefMapper;

/**
 * 流程节点提示工具：判断某条审批流程是否「多节点」。
 *
 * <p>V75 通用化后，VO.currentNodeName 承接「单据卡在第几级」的展示职责（层级由 Flowable 当前
 * 活跃任务的 taskName 表达）。但**单节点流程没有「第几级」的概念**：发货 / 收款 / 发票三条流程
 * 都只有一步审批（见 V50 / V60 种子），此时回填的 taskName 恒等于流程名（如「发货审批」），
 * 列表里只会多出一行与业务类型重复的噪音。因此只有多节点流程才回填 currentNodeName。</p>
 *
 * <p>调用点固定落在「审批中(APPROVING) 且已有 processInstanceId」的分支内（该分支本来就要
 * 调 engine.listTasks 查 Flowable），所以这里多一次按 process_code 唯一键的 selectOne
 * 与之同量级，不额外引入新的批量问题。流程定义属低频配置数据，不做缓存——改了定义立刻生效，
 * 避免静态缓存带来的「改了但没变」类疑难杂症。</p>
 */
public final class FlowNodeUtil {

    private FlowNodeUtil() {
    }

    /**
     * 判断指定流程是否为多节点流程（节点数 &gt; 1）。
     *
     * @param mapper      ProcessDefMapper（sys_process_def）
     * @param processCode 流程编码，如 shipment_approval / return_approval
     * @return 节点数大于 1 时返回 true；流程定义缺失或只有单节点时返回 false
     */
    public static boolean isMultiNode(ProcessDefMapper mapper, String processCode) {
        if (mapper == null || processCode == null) {
            return false;
        }
        ProcessDef def = mapper.selectOne(new LambdaQueryWrapper<ProcessDef>()
                .eq(ProcessDef::getProcessCode, processCode)
                .last("LIMIT 1"));
        return def != null && def.getNodeList() != null && def.getNodeList().size() > 1;
    }
}
