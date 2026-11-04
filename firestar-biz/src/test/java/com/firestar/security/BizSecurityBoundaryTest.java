package com.firestar.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.firestar.ai.controller.AiPluginController;
import com.firestar.ai.core.CozePluginVerifier;
import com.firestar.ai.service.AiChatService;
import com.firestar.ai.tool.ToolExecutor;
import com.firestar.controller.ReceivableController;
import com.firestar.controller.WriteOffController;
import com.firestar.entity.Receivable;
import com.firestar.entity.User;
import com.firestar.entity.WriteOff;
import com.firestar.exception.GlobalExceptionHandler;
import com.firestar.mapper.DictDataMapper;
import com.firestar.mapper.InvoiceAllocationMapper;
import com.firestar.mapper.InvoiceMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceiptMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.ShipmentLineMapper;
import com.firestar.mapper.ShipmentMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.WriteOffMapper;
import com.firestar.service.UserRoleService;
import com.firestar.service.impl.ReceivableServiceImpl;
import com.firestar.service.impl.WriteOffServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端点级安全契约（standalone MockMvc，真实 Controller + 真实 Service，仅 Mapper 打桩，
 * 不启动 Spring 上下文、不连数据库）。四条断言对应 P1 越权与匿名入口：
 * <ol>
 *   <li>应收详情越权 → 404</li>
 *   <li>核销反核销越权 → 404</li>
 *   <li>插件回调无签名 → 401</li>
 * </ol>
 * （第 4 条「AI 草稿越权 404」在 core 的 {@code AiDraftEndpointSecurityTest}。）
 *
 * <p>{@code @RequirePerm} 属 AOP 层，standalone 不加载；这里验证的是
 * 「已通过权限校验后，越权数据仍被归属闸拦下」。</p>
 */
@ExtendWith(MockitoExtension.class)
class BizSecurityBoundaryTest {

    @Mock private ReceivableMapper receivableMapper;
    @Mock private ShipmentMapper shipmentMapper;
    @Mock private ShipmentLineMapper shipmentLineMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private DictDataMapper dictDataMapper;
    @Mock private UserMapper userMapper;
    @Mock private WriteOffMapper writeOffMapper;
    @Mock private InvoiceAllocationMapper invoiceAllocationMapper;
    @Mock private ReceiptMapper receiptMapper;
    @Mock private InvoiceMapper invoiceMapper;
    @Mock private AiChatService chatService;
    @Mock private UserRoleService userRoleService;
    @Mock private ToolExecutor toolExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // standalone 未启动 Spring 上下文，LambdaQueryWrapper<User> 需要 TableInfo 缓存，
        // 否则抛 MybatisPlusException 被兜底 handler 吞成 200。
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), User.class);
    }

    @BeforeEach
    void setUp() {
        // 非管理员身份：authorities 只有 ROLE_STAFF，SecurityContextUtils.isAdmin() == false
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("staff", null,
                        List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private User currentUser() {
        User me = new User();
        me.setId(2L);
        me.setUsername("staff");
        return me;
    }

    /** 1. 应收详情越权：应收单属于用户 1，当前用户 2 按 id 直取 → 404 */
    @Test
    void receivableDetail_otherUsersReceivable_returns404() throws Exception {
        Receivable other = new Receivable();
        other.setId(123L);
        other.setApplicantId(1L);
        other.setShipperId(1L);
        when(receivableMapper.selectById(123L)).thenReturn(other);
        when(userMapper.selectOne(any())).thenReturn(currentUser());

        ReceivableServiceImpl service = new ReceivableServiceImpl(
                receivableMapper, shipmentMapper, shipmentLineMapper,
                projectMapper, dictDataMapper, userMapper);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReceivableController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/receivable/123"))
                .andExpect(status().isNotFound());
    }

    /** 2. 核销反核销越权：台账对应应收单属于用户 1，当前用户 2 反核销 → 404 */
    @Test
    void writeOffReverse_otherUsersLedger_returns404() throws Exception {
        WriteOff ledger = new WriteOff();
        ledger.setId(500L);
        ledger.setStatus("NORMAL");
        ledger.setReceivableId(100L);
        when(writeOffMapper.selectById(500L)).thenReturn(ledger);

        Receivable other = new Receivable();
        other.setId(100L);
        other.setApplicantId(1L);
        other.setShipperId(1L);
        when(receivableMapper.selectById(100L)).thenReturn(other);
        when(userMapper.selectOne(any())).thenReturn(currentUser());

        WriteOffServiceImpl service = new WriteOffServiceImpl(
                writeOffMapper, invoiceAllocationMapper, receiptMapper,
                receivableMapper, invoiceMapper, projectMapper, userMapper);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WriteOffController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        String body = "{\"ledgerType\":\"RECEIPT\",\"ledgerId\":500,\"reason\":\"越权测试\"}";
        mvc.perform(post("/writeoff/reverse")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    /** 3. 插件回调无签名：验签失败 → 401（真实 CozePluginVerifier，空 secret 拒绝一切） */
    @Test
    void cozeCallback_withoutSignature_returns401() throws Exception {
        CozePluginVerifier verifier = new CozePluginVerifier(""); // 未配置 secret → 所有回调被拒
        AiPluginController controller = new AiPluginController(
                verifier, chatService, userMapper, userRoleService, toolExecutor, objectMapper);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/ai/coze/plugin")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
