package com.firestar.ai.tool;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.egress.EgressMasker;
import com.firestar.ai.kb.KbEgressPolicy;
import com.firestar.ai.kb.KbRetriever;
import com.firestar.ai.spi.ToolCall;
import com.firestar.ai.spi.ToolResult;
import com.firestar.ai.spi.ToolSpec;
import com.firestar.dto.CustomerOptionVO;
import com.firestar.dto.InvoiceVO;
import com.firestar.dto.OrgVO;
import com.firestar.dto.ReceivableVO;
import com.firestar.dto.ReturnVO;
import com.firestar.dto.WriteOffLedgerVO;
import com.firestar.service.CustomerService;
import com.firestar.service.FileService;
import com.firestar.service.InvoiceService;
import com.firestar.service.LogService;
import com.firestar.service.OrgService;
import com.firestar.service.ProcessDefService;
import com.firestar.service.ProjectService;
import com.firestar.service.ReceivableService;
import com.firestar.service.ReturnService;
import com.firestar.service.ShipmentService;
import com.firestar.service.StatsService;
import com.firestar.service.UserService;
import com.firestar.service.WriteOffService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 对称检索入口补齐的单元验证：真实构造 {@link ToolExecutor}，仅对各业务 Service 打桩，
 * 覆盖 search_customers / search_receivables / search_returns / search_writeoffs /
 * search_invoices 五个新增分支，确认「注册了就真能用、不空壳、不报异常」；
 * 并验证核销/发票财务台账的权限闸（无权限用户被拒，杜绝经 AI 越权）。
 *
 * <p>不启动 Spring 上下文、不连数据库；EgressMasker 打桩为原样透传，
 * ToolRegistry 默认全部 isRegistered=true，便于单独验证执行分支。</p>
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutorSearchTest {

    @Mock private ProjectService projectService;
    @Mock private ShipmentService shipmentService;
    @Mock private ReturnService returnService;
    @Mock private ReceivableService receivableService;
    @Mock private CustomerService customerService;
    @Mock private WriteOffService writeOffService;
    @Mock private InvoiceService invoiceService;
    @Mock private StatsService statsService;
    @Mock private EgressMasker egressMasker;
    @Mock private KbRetriever kbRetriever;
    @Mock private KbEgressPolicy kbEgressPolicy;
    @Mock private OrgService orgService;
    @Mock private UserService userService;
    @Mock private LogService logService;
    @Mock private ProcessDefService processDefService;
    @Mock private FileService fileService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    // 用真实 ToolRegistry：权限码就是生产配置，测试直接验证「注册即带正确闸门」，不用 mock 猜
    private final ToolRegistry registry = new ToolRegistry();
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        // 清掉并重置干净身份：非管理员、无任何 PERM:* 权限。
        // SecurityContextHolder 是静态 ThreadLocal，会被同 JVM 其他测试类串味，
        // 这里显式覆盖，确保权限闸测试可复现（hasPerm 不会误判有权）。
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("staff", null,
                        List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
        lenient().when(egressMasker.mask(anyString())).thenAnswer(inv -> inv.getArgument(0));
        executor = new ToolExecutor(projectService, shipmentService, returnService,
                receivableService, customerService, writeOffService, invoiceService,
                statsService, registry, egressMasker, objectMapper, kbRetriever, kbEgressPolicy,
                orgService, userService, logService, processDefService, fileService);
    }

    private ToolResult run(String name, String argsJson) {
        return executor.execute(new ToolCall("call-1", name, argsJson), "staff", false);
    }

    /** 以管理员身份执行（通过权限闸） */
    private ToolResult runAsAdmin(String name, String argsJson) {
        return executor.execute(new ToolCall("call-1", name, argsJson), "admin", true);
    }

    @AfterEach
    void tearDown() {
        // 清掉本测试设置的身份，避免反向串味给同 JVM 其他测试类
        SecurityContextHolder.clearContext();
    }

    /** search_customers：keyword 命中，返回瘦 VO 数组（不含敏感字段） */
    @Test
    void search_customers_returnsMatches() {
        CustomerOptionVO c1 = new CustomerOptionVO();
        c1.setId(1L); c1.setCustomerName("北京测试客户"); c1.setCustomerNo("C001");
        CustomerOptionVO c2 = new CustomerOptionVO();
        c2.setId(2L); c2.setCustomerName("上海测试客户"); c2.setCustomerNo("C002");
        when(customerService.search(eq("测试"), anyInt())).thenReturn(List.of(c1, c2));

        ToolResult r = run("search_customers", "{\"keyword\":\"测试\",\"limit\":10}");

        assertFalse(r.isError());
        JsonNode arr = readArray(r.contentJson());
        assertEquals(2, arr.size());
        assertEquals("北京测试客户", arr.get(0).get("customerName").asText());
    }

    /** search_receivables：按 projectId + status 过滤，返回应收 VO 数组 */
    @Test
    void search_receivables_returnsMatches() {
        ReceivableVO v = new ReceivableVO();
        v.setId(7L); v.setReceivableNo("AR-7"); v.setProjectId(5L);
        IPage<ReceivableVO> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(v));
        when(receivableService.page(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(page);

        ToolResult r = runAsAdmin("search_receivables", "{\"projectId\":5,\"status\":\"UNPAID\"}");

        assertFalse(r.isError());
        JsonNode arr = readArray(r.contentJson());
        assertEquals(1, arr.size());
        assertEquals("AR-7", arr.get(0).get("receivableNo").asText());
    }

    /** search_returns：按 returnNo + 日期区间过滤，返回退货 VO 数组 */
    @Test
    void search_returns_returnsMatches() {
        ReturnVO v = new ReturnVO();
        v.setId(9L); v.setReturnNo("R-9");
        IPage<ReturnVO> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(v));
        when(returnService.page(any(), anyBoolean(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(page);

        ToolResult r = runAsAdmin("search_returns", "{\"returnNo\":\"R-9\",\"dateFrom\":\"2026-01-01\"}");

        assertFalse(r.isError());
        JsonNode arr = readArray(r.contentJson());
        assertEquals(1, arr.size());
        assertEquals("R-9", arr.get(0).get("returnNo").asText());
    }

    /** search_writeoffs：管理员经权限闸后调用 pageLedger，返回核销台账数组 */
    @Test
    void search_writeoffs_admin_returnsMatches() {
        WriteOffLedgerVO v = new WriteOffLedgerVO();
        v.setId(3L); v.setLedgerNo("WO-3");
        IPage<WriteOffLedgerVO> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(v));
        when(writeOffService.pageLedger(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(page);

        ToolResult r = runAsAdmin("search_writeoffs", "{\"projectId\":5,\"ledgerType\":\"RECEIPT\"}");

        assertFalse(r.isError());
        JsonNode arr = readArray(r.contentJson());
        assertEquals(1, arr.size());
        assertEquals("WO-3", arr.get(0).get("ledgerNo").asText());
    }

    /** search_invoices：管理员经权限闸后调用 page，返回发票数组 */
    @Test
    void search_invoices_admin_returnsMatches() {
        InvoiceVO v = new InvoiceVO();
        v.setId(4L); v.setInvoiceNo("INV-4");
        IPage<InvoiceVO> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(v));
        when(invoiceService.page(any(), anyBoolean(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(page);

        ToolResult r = runAsAdmin("search_invoices", "{\"status\":\"ISSUED\",\"projectName\":\"测试\"}");

        assertFalse(r.isError());
        JsonNode arr = readArray(r.contentJson());
        assertEquals(1, arr.size());
        assertEquals("INV-4", arr.get(0).get("invoiceNo").asText());
    }

    /** 无权限用户查财务台账被闸拒（与 Web @RequirePerm 同口径，杜绝越权） */
    @Test
    void search_writeoffs_withoutPerm_isRejected() {
        // run() 用非管理员身份且无 SecurityContext 权限，hasPerm 应返回 false
        ToolResult r = run("search_writeoffs", "{\"keyword\":\"WO-\"}");
        assertTrue(r.isError());
        assertTrue(r.contentJson().contains("无权限"));
    }

    /** 未注册工具被白名单拒绝（防越权工具调用） */
    @Test
    void unknownTool_isRejected() {
        ToolResult r = run("bogus_tool", "{}");
        assertTrue(r.isError());
        assertTrue(r.contentJson().contains("未知工具"));
    }

    /** 每个已注册工具都声明了权限语义：requiredPerm 要么 null（登录即可），要么非空权限码，不能空串 */
    @Test
    void everyTool_hasValidPermDeclaration() {
        for (ToolSpec spec : registry.all()) {
            assertTrue(spec.requiredPerm() == null || !spec.requiredPerm().isBlank(),
                    "工具 " + spec.name() + " 的 requiredPerm 不能是空串");
        }
    }

    /** 权限码与 Web @RequirePerm 同口径：核销/发票/看板/知识库等敏感工具必须有非空权限码 */
    @Test
    void sensitiveTools_declareRequiredPerm() {
        assertEquals("writeoff:manage", registry.get("search_writeoffs").requiredPerm());
        assertEquals("invoice:manage", registry.get("search_invoices").requiredPerm());
        assertEquals("dashboard:view", registry.get("stats_overview").requiredPerm());
        assertEquals("ai:kb:view", registry.get("search_knowledge").requiredPerm());
        assertEquals("rbac:user", registry.get("query_users").requiredPerm());
        assertEquals("org:manage", registry.get("query_orgs").requiredPerm());
        assertEquals("log:view", registry.get("query_login_logs").requiredPerm());
        assertEquals("process:view", registry.get("query_process_defs").requiredPerm());
        assertEquals("file:view", registry.get("query_file_download_logs").requiredPerm());
    }

    /** 下发裁剪：无权限用户看不到需授权工具；管理员全可见；登录即可工具人人可见 */
    @Test
    void allVisible_filtersByPerm() {
        var none = registry.allVisible(false, Set.of());
        assertTrue(none.stream().anyMatch(t -> t.name().equals("search_customers"))); // null 权限码
        assertTrue(none.stream().noneMatch(t -> t.name().equals("query_users")));     // 需 rbac:user

        var withRbac = registry.allVisible(false, Set.of("PERM:rbac:user"));
        assertTrue(withRbac.stream().anyMatch(t -> t.name().equals("query_users")));
        assertTrue(withRbac.stream().noneMatch(t -> t.name().equals("query_orgs")));  // 需 org:manage

        assertEquals(registry.all().size(), registry.allVisible(true, Set.of()).size()); // 管理员全量
    }

    /** 新工具无权限时被执行闸拒绝（双闸的第二道） */
    @Test
    void newTools_withoutPerm_areRejected() {
        assertTrue(run("query_users", "{}").contentJson().contains("无权限"));
        assertTrue(run("query_orgs", "{}").contentJson().contains("无权限"));
        assertTrue(run("query_login_logs", "{}").contentJson().contains("无权限"));
        assertTrue(run("query_operation_logs", "{}").contentJson().contains("无权限"));
        assertTrue(run("query_process_defs", "{}").contentJson().contains("无权限"));
        assertTrue(run("query_file_download_logs", "{}").contentJson().contains("无权限"));
    }

    /** query_orgs：单字段 text 契约——组织树拍成缩进文本，扣子 schema 只需一个 string 字段 */
    @Test
    void query_orgs_returnsSingleTextField() throws Exception {
        OrgVO child = new OrgVO();
        child.setId(2L);
        child.setOrgName("研发部");
        child.setLeaderName("李四");
        child.setStatus("ENABLED");

        OrgVO root = new OrgVO();
        root.setId(1L);
        root.setOrgName("总部");
        root.setLeaderName("杨虎宁");
        root.setStatus("ENABLED");
        root.setChildren(List.of(child));

        when(orgService.listTree()).thenReturn(List.of(root));

        ToolResult result = runAsAdmin("query_orgs", "{}");
        assertFalse(result.isError(), "管理员执行 query_orgs 不该报错");
        JsonNode node = objectMapper.readTree(result.contentJson());
        assertTrue(node.has("text"), "必须是单字段 text 契约");
        assertEquals(1, node.size(), "只允许 text 一个字段，杜绝嵌套结构漂移");
        String text = node.get("text").asText();
        assertTrue(text.contains("总部"), "文本须含部门名");
        assertTrue(text.contains("研发部"), "文本须含子部门名");
        assertTrue(text.contains("杨虎宁") && text.contains("李四"), "文本须含负责人姓名");
        assertTrue(text.contains("1.1"), "文本须体现层级编号");
    }

    private JsonNode readArray(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            assertTrue(node.isArray(), "期望返回 JSON 数组，实际：" + json);
            return node;
        } catch (Exception e) {
            throw new AssertionError("结果不是合法 JSON：" + json, e);
        }
    }
}
