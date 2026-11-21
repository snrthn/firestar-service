package com.firestar.ai.tool;

import com.firestar.ai.spi.ToolSpec;
import com.firestar.config.JwtAuthenticationFilter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表（设计 §6）。全项目工具 schema 的唯一真源，只定义一份。
 *
 * <p>职责：
 * <ul>
 *   <li>① 白名单：{@link #isRegistered(String)} 是工具执行的硬闸门——扣子插件回调 / OpenAI tool_calls
 *       命中的工具名必须在此表内，否则拒绝执行（防供应商侧被篡改调用未授权工具）；</li>
 *   <li>② 权限闸门：每个工具声明 {@code requiredPerm}（与 Web @RequirePerm 同口径）。
 *       {@link #allVisible(boolean, Set)} 按当前用户权限裁剪下发给模型的工具清单——
 *       模型看不见的工具调用不了；{@code ToolExecutor} 执行前再校验一次，防绕过；</li>
 *   <li>③ 供扣子控制台注册插件时复用同一份 schema（启动期一次性同步，不在业务调用链里）。</li>
 * </ul>
 * 工具执行一律走 {@link ToolExecutor}，以当前登录用户身份运行，复用现有 Service 的行级权限。</p>
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolSpec> specs = new ConcurrentHashMap<>();

    public ToolRegistry() {
        // ===== 业务单据查询（权限码与 Web Controller 类级 @RequirePerm 同口径）=====
        register("query_project_detail",
                "按立项单 ID 查询项目立项详情（审批状态、客户、金额、签约状态）",
                """
                {"type":"object","properties":{"projectId":{"type":"integer","description":"立项单 ID"}},"required":["projectId"]}""",
                "project:manage");
        register("query_shipment_detail",
                "按发货单 ID 查询发货详情（生命周期状态、承运商、明细行、关联应收单）",
                """
                {"type":"object","properties":{"shipmentId":{"type":"integer","description":"发货单 ID"}},"required":["shipmentId"]}""",
                "shipment:manage");
        register("query_return_detail",
                "按退货单 ID 查询退货详情（审批状态、冲减明细、审批时间线）",
                """
                {"type":"object","properties":{"returnId":{"type":"integer","description":"退货单 ID"}},"required":["returnId"]}""",
                "return:manage");
        register("query_receivable_detail",
                "按应收单 ID 查询应收详情（金额、核销/开票状态）",
                """
                {"type":"object","properties":{"receivableId":{"type":"integer","description":"应收单 ID"}},"required":["receivableId"]}""",
                "receivable:view");
        register("search_projects",
                "按项目编号关键词模糊搜索当前用户有权看到的立项单（返回 id 列表供 detail 工具跟进）",
                """
                {"type":"object","properties":{"keyword":{"type":"string","description":"项目编号关键词"},"pageNum":{"type":"integer"},"pageSize":{"type":"integer"}}}""",
                "project:manage");
        register("search_shipments",
                "按发货批次号关键词搜索当前用户有权看到的发货单",
                """
                {"type":"object","properties":{"keyword":{"type":"string","description":"发货批次号关键词"}}}""",
                "shipment:manage");
        // 客户/货物名是立项人本来就要看的（Web Controller 无 @RequirePerm），权限码 null=登录即可
        register("search_customers",
                "按客户名称模糊搜索客户主数据（返回 id/编号/名称/联系电话）。客户是业务核心往来单位，" +
                "是 stats_* 看板 customerId 过滤的前置依赖——先搜到客户 ID，才能拉「某客户的经营看板」。",
                """
                {"type":"object","properties":{
                  "keyword":{"type":"string","description":"客户名称关键词，如「华为」「中石化」"},
                  "limit":{"type":"integer","description":"返回条数上限，默认 20，最多 100"}
                }}""",
                null);
        register("search_receivables",
                "搜索应收单列表（按项目/状态/单号/项目名/来源类型/日期区间筛选），返回应收单摘要。" +
                "需先经 search_projects 或 search_customers 取得 projectId 关联。",
                """
                {"type":"object","properties":{
                  "projectId":{"type":"integer","description":"按立项单 ID 过滤(可选)"},
                  "status":{"type":"string","description":"状态过滤(可选，如 UNWRITTEN/WRITTEN)"},
                  "receivableNo":{"type":"string","description":"应收单号模糊(可选)"},
                  "projectName":{"type":"string","description":"立项单名称模糊(可选)"},
                  "sourceType":{"type":"string","description":"来源类型(可选)"},
                  "dateFrom":{"type":"string","description":"应收日期起 yyyy-MM-dd(可选)"},
                  "dateTo":{"type":"string","description":"应收日期止 yyyy-MM-dd(可选)"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最多 50"}
                }}""",
                "receivable:view");
        register("search_returns",
                "搜索退货单列表（按项目编号/项目名/退货单号/审批状态/执行状态/申请日期区间筛选），返回退货单摘要。" +
                "需先经 search_projects 取得 projectId 关联。",
                """
                {"type":"object","properties":{
                  "projectNo":{"type":"string","description":"立项单编号模糊(可选)"},
                  "projectName":{"type":"string","description":"立项单名称模糊(可选)"},
                  "returnNo":{"type":"string","description":"退货单号模糊(可选)"},
                  "status":{"type":"string","description":"审批状态(可选，如 PENDING/APPROVED/REJECTED)"},
                  "executeStatus":{"type":"string","description":"执行状态(可选)"},
                  "dateFrom":{"type":"string","description":"申请日期起 yyyy-MM-dd(可选)"},
                  "dateTo":{"type":"string","description":"申请日期止 yyyy-MM-dd(可选)"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最多 50"}
                }}""",
                "return:manage");
        register("search_writeoffs",
                "搜索核销台账（回款核销/开票核销合并视图，按立项单/台账类型/状态/核销单号/日期区间筛选），" +
                "返回核销记录摘要。仅财务权限（writeoff:manage）可用，与 Web 核销台账同口径——" +
                "无权限用户经 AI 同样查不到，杜绝越权。",
                """
                {"type":"object","properties":{
                  "projectId":{"type":"integer","description":"按立项单 ID 过滤(可选)"},
                  "ledgerType":{"type":"string","description":"台账类型：RECEIPT=回款核销 / INVOICE=开票核销(可选，不传=全部)"},
                  "status":{"type":"string","description":"状态：NORMAL / REVERSED(可选，不传=全部)"},
                  "keyword":{"type":"string","description":"核销单号模糊(可选，如 WO-/IA-)"},
                  "dateFrom":{"type":"string","description":"核销日期起 yyyy-MM-dd(可选)"},
                  "dateTo":{"type":"string","description":"核销日期止 yyyy-MM-dd(可选)"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最多 50"}
                }}""",
                "writeoff:manage");
        register("search_invoices",
                "搜索发票列表（按立项单/应收单/状态/发票号/项目名/发票类型/票据方向/日期区间筛选），" +
                "返回发票摘要。仅财务权限（invoice:manage）可用；行级隔离：非管理员/非开票员只看本人申请的发票，与 Web 一致。",
                """
                {"type":"object","properties":{
                  "projectId":{"type":"integer","description":"按立项单 ID 过滤(可选)"},
                  "receivableId":{"type":"integer","description":"按应收单 ID 过滤(可选)"},
                  "status":{"type":"string","description":"审批状态(可选，如 PENDING/APPROVING/ISSUED/REJECTED)"},
                  "invoiceNo":{"type":"string","description":"发票号模糊(可选)"},
                  "projectName":{"type":"string","description":"立项单名称模糊(可选)"},
                  "invoiceType":{"type":"string","description":"发票类型(可选)"},
                  "docDirection":{"type":"string","description":"票据方向(可选)"},
                  "dateFrom":{"type":"string","description":"开票日期起 yyyy-MM-dd(可选)"},
                  "dateTo":{"type":"string","description":"开票日期止 yyyy-MM-dd(可选)"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最多 50"}
                }}""",
                "invoice:manage");
        register("my_pending_tasks",
                "查询当前登录用户的审批待办（需要处理的工作流任务）",
                """
                {"type":"object","properties":{}}""",
                null);

        // ===== 阶段二·草稿态（AI 拟单，只读预览，不落库）=====
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
                },"required":["projectId","lines"]}""",
                null);

        // ===== L2 看板解读工具：Web StatsController 类级 @RequirePerm("dashboard:view") =====
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
        register("stats_overview", "经营总览看板（合同签约额/发货额/应收发生额/开票额/回款额等），用于解读全局经营健康", statsSchema, "dashboard:view");
        register("stats_ar_summary", "应收与回款摘要看板（不含账龄/DSO）", statsSchema, "dashboard:view");
        register("stats_invoice_summary", "开票与票税看板", statsSchema, "dashboard:view");
        register("stats_project_ranking", "项目与销售榜单（按合同额降序）", statsSchema, "dashboard:view");
        register("stats_fulfillment", "履约与风控看板（非 Flowable 指标）", statsSchema, "dashboard:view");
        register("stats_ageing", "账龄与周转看板（FIFO 账龄五段/DSO/年化周转率/平均逾期天数）", statsSchema, "dashboard:view");
        register("stats_platform", "工作台平台指标（活跃用户总数+今日登录次数）", statsSchema, "dashboard:view");

        // ===== 知识库检索：Web KbNodeController 类级 @RequirePerm("ai:kb:view") =====
        register("search_knowledge",
                "在企业知识库中检索与用户问题相关的知识片段。知识库包含：① 已发布的 Markdown 制度/操作文档；" +
                "② 帮助中心常见问题。当用户询问系统功能、操作指引、制度规范、如何办理某事、忘记密码/权限等" +
                "自助类问题时使用。返回每条片段的标题、所属章节、正文与出处引用（sourceRef）。",
                """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"检索问题或关键词，如「如何报销」「忘记密码怎么办」「侧边栏菜单看不到」"},
                  "topK":{"type":"integer","description":"返回片段数，默认 4，最多 8"}
                },"required":["query"]}""",
                "ai:kb:view");

        // ===== 组织 / 人员 / 日志 / 流程定义 / 文件（路线 2 新增，全部只读 + 权限码绑定）=====
        register("query_orgs",
                "查询组织架构树（部门名称、层级、负责人姓名）。用于回答「某部门负责人是谁」「组织架构是怎样的」。" +
                "返回整棵树，含每个部门的 leaderName（负责人姓名）。",
                """
                {"type":"object","properties":{}}""",
                "org:manage");
        register("query_users",
                "分页查询系统用户列表（可按账号/姓名/状态/部门筛选），返回账号、姓名、部门、岗位、手机、邮箱、状态。" +
                "用于回答「某部门的员工有哪些」「查某人的信息」。仅持 rbac:user 权限（Web 用户管理页同口径）可用。",
                """
                {"type":"object","properties":{
                  "username":{"type":"string","description":"账号模糊(可选)"},
                  "nickname":{"type":"string","description":"姓名模糊(可选)"},
                  "status":{"type":"string","description":"状态精确(可选)"},
                  "deptId":{"type":"integer","description":"部门 ID 精确(可选)"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最多 100"}
                }}""",
                "rbac:user");
        register("query_login_logs",
                "分页查询登录日志（按账号/状态/时间区间筛选），返回登录账号、IP、时间、状态。" +
                "仅持 log:view 权限（Web 日志页同口径）可用。",
                """
                {"type":"object","properties":{
                  "username":{"type":"string","description":"登录账号模糊(可选)"},
                  "status":{"type":"string","description":"登录状态精确(可选)"},
                  "startTime":{"type":"string","description":"起始时间 yyyy-MM-dd[ HH:mm[:ss]](可选)"},
                  "endTime":{"type":"string","description":"结束时间(可选)"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最多 50"}
                }}""",
                "log:view");
        register("query_operation_logs",
                "分页查询操作审计日志（按操作人/目标类型/操作类型/时间区间筛选），返回操作人、操作类型、目标、时间、详情。" +
                "仅持 log:view 权限（Web 日志页同口径）可用。",
                """
                {"type":"object","properties":{
                  "operator":{"type":"string","description":"操作人模糊(可选)"},
                  "targetType":{"type":"string","description":"目标类型精确(可选)"},
                  "operationType":{"type":"string","description":"操作类型精确(可选)"},
                  "startTime":{"type":"string","description":"起始时间(可选)"},
                  "endTime":{"type":"string","description":"结束时间(可选)"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最多 50"}
                }}""",
                "log:view");
        register("query_process_defs",
                "查询流程定义列表（流程编码、名称、版本、状态、节点配置）。用于回答「系统有哪些审批流程」「某流程的流转节点」。",
                """
                {"type":"object","properties":{}}""",
                "process:view");
        register("search_files",
                "按关键词/业务类型/文件类型搜索文件（返回文件名、业务类型、大小、上传时间）。" +
                "与 Web 文件列表同口径（登录即可）。不返回文件内容，仅元数据。",
                """
                {"type":"object","properties":{
                  "keyword":{"type":"string","description":"文件名关键词(可选)"},
                  "bizType":{"type":"string","description":"业务类型过滤(可选)"},
                  "bizId":{"type":"integer","description":"业务 ID 过滤(可选)"},
                  "fileType":{"type":"string","description":"文件类型过滤(可选)"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最多 50"}
                }}""",
                null);
        register("query_file_download_logs",
                "查询文件下载记录（按关键词/指定文件筛选），返回下载人、时间、IP。仅持 file:view 权限可用。",
                """
                {"type":"object","properties":{
                  "fileId":{"type":"integer","description":"指定文件 ID(可选，不传=全部)"},
                  "keyword":{"type":"string","description":"关键词(可选)"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最多 50"}
                }}""",
                "file:view");
    }

    private void register(String name, String desc, String schema, String requiredPerm) {
        specs.put(name, new ToolSpec(name, desc, schema, requiredPerm));
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

    /**
     * 按当前用户权限裁剪可见工具：管理员全放行；非管理员只拿到 requiredPerm=null 或持有对应 PERM 的工具。
     * 这是「模型看不见就调不了」的第一道闸；{@code ToolExecutor} 执行前再做一次硬校验兜底。
     */
    public List<ToolSpec> allVisible(boolean isAdmin, Set<String> authorities) {
        if (isAdmin) {
            return all();
        }
        Set<String> owned = authorities == null ? Set.of() : authorities;
        return specs.values().stream()
                .filter(t -> t.requiredPerm() == null
                        || owned.contains(JwtAuthenticationFilter.PERM_AUTHORITY_PREFIX + t.requiredPerm()))
                .toList();
    }
}
