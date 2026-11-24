package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.firestar.dto.ChangeCreateReq;
import com.firestar.dto.ChangeItemReq;
import com.firestar.dto.TaskInfo;
import com.firestar.entity.Item;
import com.firestar.entity.Project;
import com.firestar.entity.ProjectChange;
import com.firestar.entity.ProjectChangeItem;
import com.firestar.entity.User;
import com.firestar.mapper.DictDataMapper;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.ProjectChangeItemMapper;
import com.firestar.mapper.ProjectChangeMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.NotificationService;
import com.firestar.workflow.WorkflowEngineService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 项目变更单回归（V220）。
 *
 * <p>锁定四条核心契约：</p>
 * <ol>
 *   <li>窗口判定：无下游单据=A（自由改），有已发/应收/开票/收款=B（受限）；</li>
 *   <li>发起校验：原因必填、金额与明细至少一项、单价必填 >0、仅 APPROVED 可发起；</li>
 *   <li>增量 diff：ADD 插新行、DELETE 只删未发行、MODIFY 只改未发部分；</li>
 *   <li>窗口 B 金额下限：新金额不得低于已开票/已收款较大者。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ProjectChangeTest {

    @Mock private ProjectChangeMapper changeMapper;
    @Mock private ProjectChangeItemMapper changeItemMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ItemMapper itemMapper;
    @Mock private DictDataMapper dictDataMapper;
    @Mock private UserMapper userMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private WorkflowEngineService engine;
    @Mock private NotificationService notificationService;

    @InjectMocks private ProjectChangeServiceImpl service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), ProjectChange.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), ProjectChangeItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), Item.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), Project.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), com.firestar.entity.User.class);
    }

    @BeforeEach
    void setUpSecurity() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "n/a", Collections.emptyList()));
        // lenient：多数校验分支在 currentUserId 之前就抛错，用不到该 stub
        lenient().when(userMapper.selectOne(any())).thenReturn(null);
    }

    private Project approvedProject() {
        Project p = new Project();
        p.setId(1L);
        p.setProjectName("测试项目");
        p.setApprovalStatus("APPROVED");
        p.setContractAmount(new BigDecimal("100.00"));
        p.setApplicantUsername("tester");
        return p;
    }

    private ChangeCreateReq req(String reason, BigDecimal amount, ChangeItemReq... items) {
        ChangeCreateReq r = new ChangeCreateReq();
        r.setChangeReason(reason);
        r.setNewContractAmount(amount);
        r.setItems(List.of(items));
        return r;
    }

    private ChangeItemReq addReq(String name, int qty, String price) {
        ChangeItemReq it = new ChangeItemReq();
        it.setOpType("ADD");
        it.setItemName(name);
        it.setQuantity(qty);
        it.setUnitPrice(new BigDecimal(price));
        return it;
    }

    // ========== 发起校验 ==========

    @Test
    @DisplayName("发起变更：非 APPROVED 项目拒绝")
    void create_rejectsNonApprovedProject() {
        Project p = approvedProject();
        p.setApprovalStatus("DRAFT");
        when(projectMapper.selectById(1L)).thenReturn(p);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.create(1L, req("修正", new BigDecimal("105"), addReq("安装费", 1, "5"))));
        assertTrue(ex.getMessage().contains("不可发起变更"));
        verifyNoInteractions(changeMapper);
    }

    @Test
    @DisplayName("发起变更：变更原因为空拒绝")
    void create_rejectsBlankReason() {
        when(projectMapper.selectById(1L)).thenReturn(approvedProject());

        assertThrows(IllegalArgumentException.class, () ->
                service.create(1L, req("   ", new BigDecimal("105"), addReq("安装费", 1, "5"))));
        verifyNoInteractions(changeMapper);
    }

    @Test
    @DisplayName("发起变更：金额与明细都没改拒绝")
    void create_rejectsNoChange() {
        when(projectMapper.selectById(1L)).thenReturn(approvedProject());
        ChangeCreateReq empty = new ChangeCreateReq();
        empty.setChangeReason("无变更");

        assertThrows(IllegalArgumentException.class, () -> service.create(1L, empty));
        verifyNoInteractions(changeMapper);
    }

    @Test
    @DisplayName("发起变更：明细单价为 0 拒绝（单价必填 >0）")
    void create_rejectsZeroUnitPrice() {
        when(projectMapper.selectById(1L)).thenReturn(approvedProject());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.create(1L, req("补明细", null, addReq("安装费", 1, "0"))));
        assertTrue(ex.getMessage().contains("单价必须大于 0"));
    }

    @Test
    @DisplayName("发起变更：窗口 B 下 MODIFY 数量低于已发量，提交时即拒绝（拦截前移）")
    void create_windowB_rejectsModifyBelowShippedQty() {
        Project p = approvedProject();
        when(projectMapper.selectById(1L)).thenReturn(p);
        when(itemMapper.selectCount(any())).thenReturn(2L); // 有下游单据 → 窗口 B
        Item shipped = new Item();
        shipped.setId(7L);
        shipped.setProjectId(1L);
        shipped.setItemName("空调");
        shipped.setQuantity(10);
        shipped.setShippedQty(3);
        when(itemMapper.selectById(7L)).thenReturn(shipped);

        ChangeItemReq modify = new ChangeItemReq();
        modify.setOpType("MODIFY");
        modify.setItemId(7L);
        modify.setItemName("空调");
        modify.setQuantity(2); // 低于已发 3
        modify.setUnitPrice(new BigDecimal("1"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.create(1L, req("压低数量", new BigDecimal("105"), modify)));
        assertTrue(ex.getMessage().contains("不得低于已发数量"));
        verifyNoInteractions(changeMapper); // 提交阶段就拒绝，不落变更单、不进审批
    }

    @Test
    @DisplayName("发起变更：无下游单据判定为窗口 A，创建单并拉起独立审批")
    void create_windowA_startsIndependentFlow() {
        Project p = approvedProject();
        when(projectMapper.selectById(1L)).thenReturn(p);
        when(itemMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> { ((ProjectChange) inv.getArgument(0)).setId(9L); return 1; })
                .when(changeMapper).insert(any(ProjectChange.class));
        when(engine.start(eq("project_change"), eq("9"), any())).thenReturn("proc-1");

        Long id = service.create(1L, req("修正金额", new BigDecimal("10"), addReq("安装费", 1, "5")));

        assertEquals(9L, id);
        ArgumentCaptor<ProjectChange> cap = ArgumentCaptor.forClass(ProjectChange.class);
        verify(changeMapper, times(2)).updateById(cap.capture());
        ProjectChange saved = cap.getAllValues().get(0);
        assertEquals("A", saved.getWindowType());
        assertEquals("APPROVING", saved.getApprovalStatus());
        verify(engine).start(eq("project_change"), eq("9"), any());
        verify(changeItemMapper).insert(any(ProjectChangeItem.class));
    }

    @Test
    @DisplayName("发起变更：已有发货判定为窗口 B")
    void create_withShipment_isWindowB() {
        Project p = approvedProject();
        when(projectMapper.selectById(1L)).thenReturn(p);
        when(itemMapper.selectCount(any())).thenReturn(2L);
        doAnswer(inv -> { ((ProjectChange) inv.getArgument(0)).setId(9L); return 1; })
                .when(changeMapper).insert(any(ProjectChange.class));
        when(engine.start(any(), any(), any())).thenReturn("proc-1");

        service.create(1L, req("调整", new BigDecimal("105")));

        ArgumentCaptor<ProjectChange> cap = ArgumentCaptor.forClass(ProjectChange.class);
        verify(changeMapper, times(2)).updateById(cap.capture());
        assertEquals("B", cap.getAllValues().get(0).getWindowType());
    }

    // ========== 增量 diff 应用（通过反射调私有 applyChange 锁定核心算法） ==========

    private void invokeApplyChange(ProjectChange change) throws Exception {
        var m = ProjectChangeServiceImpl.class.getDeclaredMethod("applyChange", ProjectChange.class);
        m.setAccessible(true);
        m.invoke(service, change);
    }

    private ProjectChange changeWith(Project p, String window, BigDecimal newAmount, ProjectChangeItem... diffs) {
        ProjectChange c = new ProjectChange();
        c.setId(9L);
        c.setProjectId(1L);
        c.setWindowType(window);
        c.setNewContractAmount(newAmount);
        c.setChangeNo("CHG-test");
        when(changeItemMapper.selectList(any())).thenReturn(List.of(diffs));
        when(projectMapper.selectById(1L)).thenReturn(p);
        return c;
    }

    @Test
    @DisplayName("应用变更：ADD 新增明细行，合同金额取明细合计")
    void applyChange_addItem_updatesAmount() throws Exception {
        Project p = approvedProject();
        p.setContractAmount(new BigDecimal("10.00"));
        ProjectChangeItem add = new ProjectChangeItem();
        add.setOpType("ADD");
        add.setItemName("安装费");
        add.setQuantity(1);
        add.setUnitPrice(new BigDecimal("5.00"));
        add.setGoodsId(0L);
        ProjectChange c = changeWith(p, "A", null, add);

        // 回算明细合计：模拟真实 DB —— insert 的行在随后 selectList 中可见
        Item existing = new Item();
        existing.setQuantity(10);
        existing.setUnitPrice(new BigDecimal("1.00"));
        existing.setTotalAmount(new BigDecimal("10.00"));
        List<Item> persisted = new ArrayList<>(List.of(existing));
        doAnswer(inv -> {
            persisted.add((Item) inv.getArgument(0));
            return 1;
        }).when(itemMapper).insert(any(Item.class));
        when(itemMapper.selectList(any())).thenAnswer(inv -> persisted);

        invokeApplyChange(c);

        verify(itemMapper).insert(argThat((Item it) ->
                "安装费".equals(it.getItemName()) && it.getGoodsId() == 0L
                        && new BigDecimal("5.00").equals(it.getTotalAmount())));
        assertEquals(0, new BigDecimal("15.00").compareTo(p.getContractAmount()),
                "仅改明细时合同金额应自动追平明细合计（原 10 + 新增 5 = 15）");
    }

    @Test
    @DisplayName("应用变更：窗口 B 删除已发行被拒绝")
    void applyChange_windowB_rejectsDeleteShippedItem() throws Exception {
        Project p = approvedProject();
        ProjectChangeItem del = new ProjectChangeItem();
        del.setOpType("DELETE");
        del.setItemId(7L);
        ProjectChange c = changeWith(p, "B", new BigDecimal("100"), del);

        Item shipped = new Item();
        shipped.setId(7L);
        shipped.setProjectId(1L);
        shipped.setItemName("空调");
        shipped.setQuantity(10);
        shipped.setShippedQty(3);
        when(itemMapper.selectById(7L)).thenReturn(shipped);

        var ex = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> invokeApplyChange(c));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertTrue(ex.getCause().getMessage().contains("不允许删除"));
        verify(itemMapper, never()).deleteById(any());
    }

    @Test
    @DisplayName("应用变更：窗口 B 金额低于已收款被拒绝")
    void applyChange_windowB_rejectsAmountBelowCollected() throws Exception {
        Project p = approvedProject();
        p.setCollectedAmount(new BigDecimal("50.00"));
        ProjectChange c = changeWith(p, "B", new BigDecimal("30.00"));
        when(itemMapper.selectList(any())).thenReturn(Collections.emptyList());

        var ex = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> invokeApplyChange(c));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertTrue(ex.getCause().getMessage().contains("不得低于已开票/已收款"));
    }

    @Test
    @DisplayName("应用变更：窗口 A 金额自由调小不受限")
    void applyChange_windowA_allowsLowerAmount() throws Exception {
        Project p = approvedProject();
        p.setContractAmount(new BigDecimal("100.00"));
        ProjectChange c = changeWith(p, "A", new BigDecimal("10.00"));
        when(itemMapper.selectList(any())).thenReturn(Collections.emptyList());

        invokeApplyChange(c);

        assertEquals(0, new BigDecimal("10.00").compareTo(p.getContractAmount()),
                "窗口 A 无下游单据，金额可自由改小（修正手滑多打 0）");
    }
}
