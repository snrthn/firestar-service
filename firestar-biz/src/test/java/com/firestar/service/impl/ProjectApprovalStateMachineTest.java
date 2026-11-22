package com.firestar.service.impl;

import com.firestar.dto.NodeCfg;
import com.firestar.entity.Item;
import com.firestar.entity.ProcessDef;
import com.firestar.entity.Project;
import com.firestar.entity.User;
import com.firestar.mapper.*;
import com.firestar.service.NotificationService;
import com.firestar.workflow.WorkflowEngineService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 立项审批业务状态机回归（P1 测试补齐）。
 *
 * <p>此前审批链路只有 {@code PocRejectSkipTest}（纯 Flowable 引擎 POC），
 * 业务 Service 的 submit/withdraw 守卫从未被测试触达。本类用 Mockito 把
 * 18 个依赖全部隔离，只验证业务侧决策逻辑（状态闸门、重提终结旧实例、
 * 撤回终态），Flowable 引擎语义仍由 POC 测试覆盖，两层互不重复。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProjectApprovalStateMachineTest {

    @Mock private ProjectMapper mapper;
    @Mock private ProcessDefMapper processDefMapper;
    @Mock private DictDataMapper dictDataMapper;
    @Mock private WorkflowEngineService engine;
    @Mock private NotificationService notificationService;
    @Mock private UserMapper userMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private ShipmentMapper shipmentMapper;
    @Mock private ItemMapper itemMapper;
    @Mock private ReceiptMapper receiptMapper;
    @Mock private InvoiceMapper invoiceMapper;
    @Mock private ReceivableMapper receivableMapper;
    @Mock private ReturnOrderMapper returnOrderMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private FileStorageMapper fileStorageMapper;

    @InjectMocks private ProjectServiceImpl service;

    private Project project;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 纯 Mockito 单测无 Spring 上下文，LambdaQueryWrapper<User> 解析需要 TableInfo 缓存。
        // 沿用 BizSecurityBoundaryTest 的范式；不做这步，currentUserId() 会抛
        // MybatisPlusException「can not find lambda cache」。
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), User.class);
    }

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(1001L);
        project.setProjectName("ut-approval-project");
        project.setContractAmount(new BigDecimal("10000"));
        project.setApprovalStatus("DRAFT");
        project.setSalesManagerId(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_user", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void stubProcessDef() {
        ProcessDef def = new ProcessDef();
        def.setProcessCode(ProjectServiceImpl.INITIATE_PROCESS_CODE);
        def.setStatus("ENABLED");
        NodeCfg node = new NodeCfg();
        node.setSeq(1);
        node.setNodeId("node_1");
        node.setName("审批节点1");
        node.setType("USER");
        node.setAssignee("1");
        def.setNodeList(List.of(node));
        when(processDefMapper.selectOne(any())).thenReturn(def);
    }

    @Test
    @DisplayName("草稿提交：有明细行 + 流程已配置 → 启动引擎并置 APPROVING")
    void submit_draftWithItems_startsEngine() {
        when(mapper.selectById(1001L)).thenReturn(project);
        when(itemMapper.selectCount(any())).thenReturn(1L);
        stubProcessDef();
        when(engine.start(anyString(), anyString(), anyMap())).thenReturn("pi-1");
        when(engine.listTasks("pi-1")).thenReturn(List.of());

        service.submit(1001L);

        assertEquals("APPROVING", project.getApprovalStatus());
        assertEquals("pi-1", project.getProcessInstanceId());
        assertEquals("pm_user", project.getApplicantUsername());
        verify(mapper).updateById(project);
        verify(engine).start(eq(ProjectServiceImpl.INITIATE_PROCESS_CODE), eq("1001"), anyMap());
    }

    @Test
    @DisplayName("无明细行提交被拒：立项带行是发货的合同依据")
    void submit_noItems_rejected() {
        when(mapper.selectById(1001L)).thenReturn(project);
        when(itemMapper.selectCount(any())).thenReturn(0L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(1001L));
        assertTrue(ex.getMessage().contains("货物明细"));
        verifyNoInteractions(engine);
    }

    @Test
    @DisplayName("已通过的项目不可重复提交：状态闸门只放行 DRAFT/REJECTED")
    void submit_approved_rejected() {
        project.setApprovalStatus("APPROVED");
        when(mapper.selectById(1001L)).thenReturn(project);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(1001L));
        assertTrue(ex.getMessage().contains("仅草稿/已驳回"));
        verifyNoInteractions(engine);
    }

    @Test
    @DisplayName("驳回后重提：先终结旧流程实例，避免新旧双份待办")
    void submit_rejected_terminatesOldInstance() {
        project.setApprovalStatus("REJECTED");
        project.setProcessInstanceId("pi-old");
        when(mapper.selectById(1001L)).thenReturn(project);
        when(itemMapper.selectCount(any())).thenReturn(1L);
        stubProcessDef();
        when(engine.start(anyString(), anyString(), anyMap())).thenReturn("pi-new");
        when(engine.listTasks("pi-new")).thenReturn(List.of());

        service.submit(1001L);

        verify(engine).terminate(eq("pi-old"), contains("重新提交审批"));
        verify(engine).start(anyString(), anyString(), anyMap());
        assertEquals("pi-new", project.getProcessInstanceId());
    }

    @Test
    @DisplayName("撤回：经引擎三道闸后项目置 CANCELLED 终态（不回草稿）")
    void withdraw_runningProject_becomesCancelled() {
        project.setApprovalStatus("APPROVING");
        project.setProcessInstanceId("pi-live");
        when(mapper.selectById(1001L)).thenReturn(project);
        when(engine.withdraw(eq("pi-live"), eq("pm_user"), anyString())).thenReturn(List.of(7L));
        // currentUserId() 会查 sys_user 解析操作人，纯单测里给个空返回即可
        // （当前用户 id 只用于写审计字段，不影响状态机断言）
        when(userMapper.selectOne(any())).thenReturn(null);

        service.withdraw(1001L);

        assertEquals("CANCELLED", project.getApprovalStatus());
        verify(engine).withdraw(eq("pi-live"), eq("pm_user"), contains("撤回"));
        verify(mapper).updateById(project);
        verify(notificationService, atLeastOnce()).notify(any());
    }

    @Test
    @DisplayName("无在途流程时撤回被拒")
    void withdraw_noInstance_rejected() {
        when(mapper.selectById(1001L)).thenReturn(project);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.withdraw(1001L));
        assertTrue(ex.getMessage().contains("无需撤回"));
        verifyNoInteractions(engine);
    }
}
