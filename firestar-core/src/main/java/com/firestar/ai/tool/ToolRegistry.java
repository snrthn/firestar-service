package com.firestar.ai.tool;

import com.firestar.ai.spi.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表（设计 §6）。全项目工具 schema 的唯一真源，只定义一份。
 *
 * <p>职责：
 * <ul>
 *   <li>① 白名单：{@link #isRegistered(String)} 是工具执行的硬闸门——扣子插件回调 / OpenAI tool_calls
 *       命中的工具名必须在此表内，否则拒绝执行（防供应商侧被篡改调用未授权工具）；</li>
 *   <li>② 供扣子控制台注册插件时复用同一份 schema（启动期一次性同步，不在业务调用链里）。</li>
 * </ul>
 * 工具执行一律走 {@link ToolExecutor}，以当前登录用户身份运行，复用现有 Service 的行级权限。</p>
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolSpec> specs = new ConcurrentHashMap<>();

    public ToolRegistry() {
        register("query_project_detail",
                "按立项单 ID 查询项目立项详情（审批状态、客户、金额、签约状态）",
                """
                {"type":"object","properties":{"projectId":{"type":"integer","description":"立项单 ID"}},"required":["projectId"]}""");
        register("query_shipment_detail",
                "按发货单 ID 查询发货详情（生命周期状态、承运商、明细行、关联应收单）",
                """
                {"type":"object","properties":{"shipmentId":{"type":"integer","description":"发货单 ID"}},"required":["shipmentId"]}""");
        register("query_return_detail",
                "按退货单 ID 查询退货详情（审批状态、冲减明细、审批时间线）",
                """
                {"type":"object","properties":{"returnId":{"type":"integer","description":"退货单 ID"}},"required":["returnId"]}""");
        register("query_receivable_detail",
                "按应收单 ID 查询应收详情（金额、核销/开票状态）",
                """
                {"type":"object","properties":{"receivableId":{"type":"integer","description":"应收单 ID"}},"required":["receivableId"]}""");
        register("search_projects",
                "按项目编号关键词模糊搜索当前用户有权看到的立项单（返回 id 列表供 detail 工具跟进）",
                """
                {"type":"object","properties":{"keyword":{"type":"string","description":"项目编号关键词"},"pageNum":{"type":"integer"},"pageSize":{"type":"integer"}}}""");
        register("search_shipments",
                "按发货批次号关键词搜索当前用户有权看到的发货单",
                """
                {"type":"object","properties":{"keyword":{"type":"string","description":"发货批次号关键词"}}}""");
        register("my_pending_tasks",
                "查询当前登录用户的审批待办（需要处理的工作流任务）",
                """
                {"type":"object","properties":{}}""");

        // ===== 阶段二·草稿态（AI 拟单，只读预览，不落库） =====
        // 入参为结构化 ID（AI 已先经 search_projects + query_project_detail 取得 projectId / itemId）。
        // 后端校验超发/超合同后返回结构化预览，前端渲染「待确认表单」卡片，确认才调原 POST /shipment。
        register("draft_shipment_create",
                "根据项目 ID 与若干货物明细行，生成一张发货单草稿预览（只读，不落库）。" +
                "返回项目/客户信息、每行货物名称/规格/数量/单价/金额、合计、以及超发/超合同等错误与告警。" +
                "仅在用户确认后，才由前端调用原创建接口落库。",
                """
                {"type":"object","properties":{
                  "projectId":{"type":"integer","description":"立项单 ID（来自 search_projects / query_project_detail）"},
                  "lines":{"type":"array","description":"发货明细行","items":{"type":"object","properties":{
                    "itemId":{"type":"integer","description":"货物 ID（来自 query_project_detail 的 items）"},
                    "shipmentQty":{"type":"integer","description":"本次申请发货数量（必须为正）"}
                  },"required":["itemId","shipmentQty"]}}
                },"required":["projectId","lines"]}""");

        // ===== L2 看板解读工具（设计 §6 / 阶段二）：仅管理员可用，返回脱敏后的看板聚合数据 =====
        // 安全基线：StatsServiceImpl 内部不做行级隔离（纯靠 StatsQuery 过滤），而 StatsController
        // 整类受 @RequirePerm("dashboard:view") 门禁。故此处 L2 工具执行时由 ToolExecutor 强制
        // isAdmin 门禁，避免普通用户经 AI 拿全局财务数据（比 L1 更危险的越权面）。
        String statsSchema = """
                {"type":"object","properties":{
                  "periodPreset":{"type":"string","enum":["THIS_MONTH","THIS_QUARTER","THIS_YEAR","LAST_12_MONTH","ALL"],"description":"期间预设，默认 THIS_MONTH"},
                  "periodStart":{"type":"string","description":"期间起 yyyy-MM-dd（与 periodPreset 二选一，预设优先）"},
                  "periodEnd":{"type":"string","description":"期间止 yyyy-MM-dd"},
                  "projectId":{"type":"integer","description":"项目过滤(单选)"},
                  "customerId":{"type":"integer","description":"客户过滤"},
                  "managerId":{"type":"integer","description":"销售经理过滤(sys_user.id)"},
                  "goodsId":{"type":"integer","description":"货物过滤"}
                }}""";
        register("stats_overview", "经营总览看板（合同签约额/发货额/应收发生额/开票额/回款额等），用于解读全局经营健康", statsSchema);
        register("stats_ar_summary", "应收与回款摘要看板（不含账龄/DSO）", statsSchema);
        register("stats_invoice_summary", "开票与票税看板", statsSchema);
        register("stats_project_ranking", "项目与销售榜单（按合同额降序）", statsSchema);
        register("stats_fulfillment", "履约与风控看板（非 Flowable 指标）", statsSchema);
        register("stats_ageing", "账龄与周转看板（FIFO 账龄五段/DSO/年化周转率/平均逾期天数）", statsSchema);
        register("stats_platform", "工作台平台指标（活跃用户总数+今日登录次数）", statsSchema);
    }

    private void register(String name, String desc, String schema) {
        specs.put(name, new ToolSpec(name, desc, schema));
    }

    public boolean isRegistered(String name) {
        return name != null && specs.containsKey(name);
    }

    public ToolSpec get(String name) {
        return specs.get(name);
    }

    public List<ToolSpec> all() {
        return List.copyOf(specs.values());
    }
}
