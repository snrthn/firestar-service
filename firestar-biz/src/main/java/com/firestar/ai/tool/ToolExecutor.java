package com.firestar.ai.tool;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.firestar.ai.egress.EgressMasker;
import com.firestar.ai.kb.KbChunkHit;
import com.firestar.ai.kb.KbEgressPolicy;
import com.firestar.ai.kb.KbRetriever;
import com.firestar.ai.kb.KbSearchContext;
import com.firestar.ai.spi.ToolCall;
import com.firestar.ai.spi.ToolResult;
import com.firestar.ai.spi.ToolSpec;
import com.firestar.config.JwtAuthenticationFilter;
import com.firestar.dto.StatsQuery;
import com.firestar.service.FileService;
import com.firestar.service.InvoiceService;
import com.firestar.service.LogService;
import com.firestar.service.OrgService;
import com.firestar.service.ProcessDefService;
import com.firestar.service.ProjectService;
import com.firestar.service.ReceivableService;
import com.firestar.service.ReturnService;
import com.firestar.service.ShipmentService;
import com.firestar.service.UserService;
import com.firestar.service.WriteOffService;
import com.firestar.dto.ShipmentCreateReq;
import com.firestar.dto.ShipmentDraftPreview;
import com.firestar.dto.ShipmentLineCreateReq;
import com.firestar.service.StatsService;
import com.firestar.service.CustomerService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具执行层（设计 §6）。供应商命中的工具（扣子插件回调 / OpenAI tool_calls）最终都汇聚到这里。
 *
 * <p>安全约束：
 * <ul>
 *   <li>工具名必须先在 {@link ToolRegistry} 白名单内，否则拒绝（防未授权工具被调用）；</li>
 *   <li>执行一律以<b>当前登录用户身份</b>（username / isAdmin 来自 SecurityContext，绝不采用供应商传入的 user_id）；</li>
 *   <li>所有查询复用现有 Service，自动继承 RBAC 与行级权限（Service 方法已内建 username + viewAll 隔离）；</li>
 *   <li>结果经 {@link EgressMasker} 脱敏后才回给供应商（税号假名化 / 银行账号留尾 / 身份证脱敏 / 金额放行）。</li>
 * </ul>
 * 本执行器只走已校验归属的 Service 方法（username + viewAll 行级隔离），
 * 不碰任何裸 @RequestParam 过滤的直查端点，杜绝越权爬数。</p>
 */
@Service
public class ToolExecutor {

    private final ProjectService projectService;
    private final ShipmentService shipmentService;
    private final ReturnService returnService;
    private final ReceivableService receivableService;
    private final CustomerService customerService;
    private final WriteOffService writeOffService;
    private final InvoiceService invoiceService;
    private final StatsService statsService;
    private final ToolRegistry registry;
    private final EgressMasker egressMasker;
    private final ObjectMapper objectMapper;
    private final KbRetriever kbRetriever;
    private final KbEgressPolicy kbEgressPolicy;
    private final OrgService orgService;
    private final UserService userService;
    private final LogService logService;
    private final ProcessDefService processDefService;
    private final FileService fileService;

    public ToolExecutor(ProjectService projectService, ShipmentService shipmentService,
                        ReturnService returnService, ReceivableService receivableService,
                        CustomerService customerService, WriteOffService writeOffService,
                        InvoiceService invoiceService, StatsService statsService, ToolRegistry registry,
                        EgressMasker egressMasker, ObjectMapper objectMapper,
                        KbRetriever kbRetriever, KbEgressPolicy kbEgressPolicy,
                        OrgService orgService, UserService userService, LogService logService,
                        ProcessDefService processDefService, FileService fileService) {
        this.projectService = projectService;
        this.shipmentService = shipmentService;
        this.returnService = returnService;
        this.receivableService = receivableService;
        this.customerService = customerService;
        this.writeOffService = writeOffService;
        this.invoiceService = invoiceService;
        this.statsService = statsService;
        this.registry = registry;
        this.egressMasker = egressMasker;
        this.objectMapper = objectMapper;
        this.kbRetriever = kbRetriever;
        this.kbEgressPolicy = kbEgressPolicy;
        this.orgService = orgService;
        this.userService = userService;
        this.logService = logService;
        this.processDefService = processDefService;
        this.fileService = fileService;
    }

    public ToolResult execute(ToolCall call, String username, boolean isAdmin) {
        ToolSpec spec = registry.get(call.name());
        if (spec == null) {
            return new ToolResult(call.id(), call.name(), "{\"error\":\"未知工具，已拒绝执行\"}", true);
        }
        // 统一权限闸：每个工具在 ToolRegistry 声明 requiredPerm（与 Web @RequirePerm 同口径）。
        // 非管理员必须持有 PERM:<code> 才能执行；ADMIN 放行。这是执行前的第二道闸
        // （第一道是 AiChatController 下发时按 allVisible 裁剪），防扣子回调/伪造 tool_calls 绕过。
        // 必须放在 switch 之前早返回——switch 表达式内不允许 return。
        if (spec.requiredPerm() != null && !hasPerm(spec.requiredPerm(), isAdmin)) {
            String deny = "{\"error\":\"无权限：需要 " + spec.requiredPerm() + " 权限\"}";
            return new ToolResult(call.id(), call.name(), deny, true);
        }
        try {
            JsonNode args = (call.argumentsJson() == null || call.argumentsJson().isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(call.argumentsJson());

            String raw = switch (call.name()) {
                case "query_project_detail" ->
                        json(projectService.detail(longArg(args, "projectId"), username, isAdmin));
                case "query_shipment_detail" ->
                        json(shipmentService.detail(longArg(args, "shipmentId"), username, isAdmin));
                case "query_return_detail" ->
                        json(returnService.detail(longArg(args, "returnId"), username, isAdmin));
                case "query_receivable_detail" ->
                        json(receivableService.detail(longArg(args, "receivableId")));
                case "search_projects" -> {
                    int pageNum = intArg(args, "pageNum", 1);
                    int pageSize = Math.min(intArg(args, "pageSize", 20), 50);
                    String kw = strArg(args, "keyword");
                    IPage<?> p1 = projectService.page(username, isAdmin, pageNum, pageSize,
                            kw, null, null, null, null, null, null, null);
                    IPage<?> p2 = projectService.page(username, isAdmin, pageNum, pageSize,
                            null, kw, null, null, null, null, null, null);
                    yield mergeRecords(p1, p2);
                }
                case "search_shipments" -> {
                    IPage<?> p = shipmentService.page(username, isAdmin, 1, 20,
                            strArg(args, "keyword"), null, null, null, null, null, null);
                    yield json(p.getRecords());
                }
                // ===== 对称检索入口补齐（2026-11-09）：客户 / 应收 / 退货 =====
                case "search_customers" -> {
                    int lim = Math.min(intArg(args, "limit", 20), 100);
                    yield json(customerService.search(strArg(args, "keyword"), lim));
                }
                case "search_receivables" -> {
                    int pn = intArg(args, "pageNum", 1);
                    int ps = Math.min(intArg(args, "pageSize", 20), 50);
                    Long pid = longArg(args, "projectId");
                    IPage<?> p = receivableService.page(pn, ps,
                            pid == 0L ? null : pid,
                            strArg(args, "status"), strArg(args, "receivableNo"),
                            strArg(args, "projectName"), strArg(args, "sourceType"),
                            parseDate(strArg(args, "dateFrom")), parseDate(strArg(args, "dateTo")));
                    yield json(p.getRecords());
                }
                case "search_returns" -> {
                    int pn = intArg(args, "pageNum", 1);
                    int ps = Math.min(intArg(args, "pageSize", 20), 50);
                    IPage<?> p = returnService.page(username, isAdmin, pn, ps,
                            strArg(args, "projectNo"), strArg(args, "projectName"),
                            strArg(args, "returnNo"), strArg(args, "status"),
                            strArg(args, "executeStatus"),
                            parseDate(strArg(args, "dateFrom")), parseDate(strArg(args, "dateTo")));
                    yield json(p.getRecords());
                }
                // ===== 财务台账检索补齐（2026-11-10）：核销 / 发票，已在 switch 前做权限闸，这里只查数据 =====
                // 核销台账是全局财务视图（Web @RequirePerm("writeoff:manage")，pageLedger 无用户隔离），
                // 故仅持 writeoff:manage 或管理员可见（闸在 switch 前），看到全局台账（与 Web 一致）。
                case "search_writeoffs" -> {
                    int pn = intArg(args, "pageNum", 1);
                    int ps = Math.min(intArg(args, "pageSize", 20), 50);
                    IPage<?> p = writeOffService.pageLedger(pn, ps,
                            longArg(args, "projectId") == 0L ? null : longArg(args, "projectId"),
                            strArg(args, "ledgerType"), strArg(args, "status"),
                            strArg(args, "keyword"),
                            parseDate(strArg(args, "dateFrom")), parseDate(strArg(args, "dateTo")));
                    yield json(p.getRecords());
                }
                // 发票列表 Web @RequirePerm("invoice:manage")；page() 内部按 SecurityContext 当前用户
                // 做行级隔离（非 admin/开票员 只看 applicantId=自己），与 Web 行为一致。
                case "search_invoices" -> {
                    int pn = intArg(args, "pageNum", 1);
                    int ps = Math.min(intArg(args, "pageSize", 20), 50);
                    IPage<?> p = invoiceService.page(username, isAdmin, pn, ps,
                            longArg(args, "projectId") == 0L ? null : longArg(args, "projectId"),
                            longArg(args, "receivableId") == 0L ? null : longArg(args, "receivableId"),
                            strArg(args, "status"), strArg(args, "invoiceNo"),
                            strArg(args, "projectName"), strArg(args, "invoiceType"),
                            strArg(args, "docDirection"),
                            parseDate(strArg(args, "dateFrom")), parseDate(strArg(args, "dateTo")));
                    yield json(p.getRecords());
                }
                case "my_pending_tasks" ->
                        json(projectService.myTasks(username));
                // ===== 阶段二·草稿态：AI 拟单预览（只读，不落库、不占额度） =====
                case "draft_shipment_create" -> {
                    ShipmentCreateReq dr = new ShipmentCreateReq();
                    if (args.has("projectId")) {
                        dr.setProjectId(args.get("projectId").asLong());
                    }
                    List<ShipmentLineCreateReq> dlines = new ArrayList<>();
                    if (args.has("lines") && args.get("lines").isArray()) {
                        for (JsonNode ln : args.get("lines")) {
                            ShipmentLineCreateReq sl = new ShipmentLineCreateReq();
                            if (ln.has("itemId")) sl.setItemId(ln.get("itemId").asLong());
                            if (ln.has("shipmentQty")) sl.setShipmentQty(ln.get("shipmentQty").asInt());
                            dlines.add(sl);
                        }
                    }
                    dr.setLines(dlines);
                    ShipmentDraftPreview preview = shipmentService.buildDraftPreview(dr, username, isAdmin);
                    yield json(preview);
                }
                // ===== L2 看板解读（阶段二）：仅管理员可触发，避免普通用户经 AI 拿全局财务数据 =====
                case "stats_overview", "stats_ar_summary", "stats_invoice_summary",
                     "stats_project_ranking", "stats_fulfillment", "stats_ageing", "stats_platform" -> {
                    if (!isAdmin) {
                        yield "{\"error\":\"无看板查看权限：AI 看板解读仅管理员可用\"}";
                    }
                    StatsQuery sq = buildStatsQuery(args);
                    yield switch (call.name()) {
                        case "stats_overview" -> json(statsService.overview(sq));
                        case "stats_ar_summary" -> json(statsService.arSummary(sq));
                        case "stats_invoice_summary" -> json(statsService.invoiceSummary(sq));
                        case "stats_project_ranking" -> json(statsService.projectRanking(sq));
                        case "stats_fulfillment" -> json(statsService.fulfillment(sq));
                        case "stats_ageing" -> json(statsService.ageing(sq));
                        case "stats_platform" -> json(statsService.platformStats());
                        default -> "{\"error\":\"未实现的工具\"}";
                    };
                }
                // ===== 知识库检索（步骤 5）：以当前登录用户身份检索，ACL 在 Retriever 内过滤 =====
                case "search_knowledge" -> {
                    String q = strArg(args, "query");
                    int k = Math.min(intArg(args, "topK", 4), 8);
                    KbSearchContext ctx = new KbSearchContext(isAdmin, currentAuthorities(), 0L);
                    List<KbChunkHit> hits = kbRetriever.retrieve(q, ctx, k);
                    List<KbChunkHit> allowed = hits.stream()
                            .filter(h -> kbEgressPolicy.allow(h, ctx))
                            .collect(java.util.stream.Collectors.toList());
                    yield json(toKnowledgeResult(allowed));
                }
                // ===== 组织 / 人员 / 日志 / 流程 / 文件（路线 2 新增；权限已在 execute 开头按 requiredPerm 统一校验）=====
                case "query_orgs" -> json(orgService.listTree());
                case "query_users" -> {
                    int pn = intArg(args, "pageNum", 1);
                    int ps = Math.min(intArg(args, "pageSize", 20), 100);
                    yield json(userService.pageUsers(pn, ps, strArg(args, "username"),
                            strArg(args, "nickname"), strArg(args, "status"),
                            longArg(args, "deptId") == 0L ? null : longArg(args, "deptId")).getRecords());
                }
                case "query_login_logs" -> {
                    int pn = intArg(args, "pageNum", 1);
                    int ps = Math.min(intArg(args, "pageSize", 20), 50);
                    yield json(logService.pageLoginLogs(pn, ps, strArg(args, "username"),
                            strArg(args, "status"), parseDateTime(strArg(args, "startTime")),
                            parseDateTime(strArg(args, "endTime"))).getRecords());
                }
                case "query_operation_logs" -> {
                    int pn = intArg(args, "pageNum", 1);
                    int ps = Math.min(intArg(args, "pageSize", 20), 50);
                    yield json(logService.pageAuditLogs(pn, ps, strArg(args, "operator"),
                            strArg(args, "targetType"), strArg(args, "operationType"),
                            parseDateTime(strArg(args, "startTime")),
                            parseDateTime(strArg(args, "endTime"))).getRecords());
                }
                case "query_process_defs" -> json(processDefService.list());
                case "search_files" -> {
                    int pn = intArg(args, "pageNum", 1);
                    int ps = Math.min(intArg(args, "pageSize", 20), 50);
                    yield json(fileService.list(pn, ps, strArg(args, "bizType"),
                            longArg(args, "bizId") == 0L ? null : longArg(args, "bizId"),
                            strArg(args, "fileType"), strArg(args, "keyword")).getRecords());
                }
                case "query_file_download_logs" -> {
                    int pn = intArg(args, "pageNum", 1);
                    int ps = Math.min(intArg(args, "pageSize", 20), 50);
                    yield json(fileService.downloadLogs(
                            longArg(args, "fileId") == 0L ? null : longArg(args, "fileId"),
                            pn, ps, strArg(args, "keyword")).getRecords());
                }
                default -> "{\"error\":\"未实现的工具\"}";
            };

            String masked = egressMasker.mask(raw);
            return new ToolResult(call.id(), call.name(), masked, false);
        } catch (Exception e) {
            return new ToolResult(call.id(), call.name(),
                    "{\"error\":\"" + sanitize(e.getMessage()) + "\"}", true);
        }
    }

    /** 合并两次分页结果（按项目编号 / 名称两次模糊查询），按 id 去重 */
    private String mergeRecords(IPage<?> p1, IPage<?> p2) throws Exception {
        ArrayNode arr = objectMapper.createArrayNode();
        Set<Long> seen = new HashSet<>();
        for (Object rec : p1.getRecords()) {
            addIfNew(arr, objectMapper.valueToTree(rec), seen);
        }
        for (Object rec : p2.getRecords()) {
            addIfNew(arr, objectMapper.valueToTree(rec), seen);
        }
        return objectMapper.writeValueAsString(arr);
    }

    private void addIfNew(ArrayNode arr, JsonNode node, Set<Long> seen) {
        long id = node.has("id") ? node.get("id").asLong(-1L) : -1L;
        if (id < 0 || seen.add(id)) {
            arr.add(node);
        }
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    /** 从工具入参构造 StatsQuery（L2 看板解读）。过滤条件缺省即全局聚合，与 StatsController 语义一致。 */
    private StatsQuery buildStatsQuery(JsonNode a) {
        StatsQuery q = new StatsQuery();
        q.setPeriodPreset(strArg(a, "periodPreset"));
        String ps = strArg(a, "periodStart");
        if (ps != null && !ps.isBlank()) {
            try { q.setPeriodStart(LocalDate.parse(ps)); } catch (Exception ignored) {}
        }
        String pe = strArg(a, "periodEnd");
        if (pe != null && !pe.isBlank()) {
            try { q.setPeriodEnd(LocalDate.parse(pe)); } catch (Exception ignored) {}
        }
        long pid = longArg(a, "projectId");
        q.setProjectId(pid == 0L ? null : pid);
        long cid = longArg(a, "customerId");
        q.setCustomerId(cid == 0L ? null : cid);
        long mid = longArg(a, "managerId");
        q.setManagerId(mid == 0L ? null : mid);
        long gid = longArg(a, "goodsId");
        q.setGoodsId(gid == 0L ? null : gid);
        return q;
    }

    private long longArg(JsonNode a, String k) {
        return a.has(k) ? a.get(k).asLong() : 0L;
    }

    private int intArg(JsonNode a, String k, int d) {
        return a.has(k) ? a.get(k).asInt(d) : d;
    }

    private String strArg(JsonNode a, String k) {
        return a.has(k) ? a.get(k).asText() : null;
    }

    /** 解析 yyyy-MM-dd，空或格式错误返回 null（供 search_receivables / search_returns 的日期区间入参） */
    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sanitize(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }

    /** 把知识检索命中拼成结构化 JSON 数组（供模型引用，含出处 sourceRef / fileId） */
    private String toKnowledgeResult(List<KbChunkHit> hits) throws Exception {
        ArrayNode arr = objectMapper.createArrayNode();
        for (KbChunkHit h : hits) {
            ObjectNode o = objectMapper.createObjectNode();
            o.put("sourceType", h.sourceType());
            o.put("title", h.title());
            o.put("headingPath", h.headingPath());
            o.put("anchor", h.anchor());
            o.put("content", h.content());
            o.put("sourceRef", h.sourceRef());
            o.put("score", h.score());
            // fileId>0 表示命中来自 REFERENCE 附件，模型可据此引用附件原文
            if (h.fileId() != null && h.fileId() > 0) {
                o.put("fileId", h.fileId());
            }
            arr.add(o);
        }
        return objectMapper.writeValueAsString(arr);
    }

    /** 当前登录用户的权限集合（role_code 直灌 / PERM:xxx），与 KbDocService 计算口径一致 */
    private Set<String> currentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (a != null && a.getAuthority() != null) {
                out.add(a.getAuthority());
            }
        }
        return out;
    }

    /** 解析 yyyy-MM-dd[ HH:mm[:ss]]，空或格式错误返回 null（供日志工具的时间区间入参） */
    private LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim();
        try {
            if (v.length() == 10) {
                return LocalDate.parse(v).atStartOfDay();
            }
            return LocalDateTime.parse(v.replace(' ', 'T'));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 权限闸：与 Web {@code @RequirePerm} 同口径——ROLE_ADMIN 放行，否则需持 PERM:&lt;code&gt;。
     * 由 execute 开头对工具的 requiredPerm 统一调用，杜绝经 AI 越权爬数。
     */
    private boolean hasPerm(String permCode, boolean isAdmin) {
        if (isAdmin) {
            return true;
        }
        String need = JwtAuthenticationFilter.PERM_AUTHORITY_PREFIX + permCode;
        return currentAuthorities().contains(need);
    }
}
