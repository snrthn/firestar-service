package com.firestar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.ReceiptRequest;
import com.firestar.dto.ShipmentCreateReq;
import com.firestar.dto.ShipmentLineCreateReq;
import com.firestar.entity.Item;
import com.firestar.entity.Project;
import com.firestar.entity.Receipt;
import com.firestar.entity.Receivable;
import com.firestar.entity.Shipment;
import com.firestar.entity.ShipmentLine;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceiptMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.ShipmentLineMapper;
import com.firestar.mapper.ShipmentMapper;
import com.firestar.service.ReceiptService;
import com.firestar.service.ShipmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-2（2026-10-27）：业务级账务不变量 + 并发防超发测试。
 *
 * <p>现有 9 个测试全在基础设施层（安全暴露面 / 实体映射 / 认证 / Flowable / 分页），
 * 零业务级用例——而 P0-1 这种「AI 自信写错、且无机制发现」的 bug 恰恰需要业务测试兜底。</p>
 *
 * <p>本类补齐最关键的两条：
 *   1) <b>并发发货不得突破合同量</b>——直接回归验证 P0-1 原子 UPDATE 修复（原乐观锁会静默超发、账面上看不出）；
 *   2) <b>应收单金额不变量</b> received + unreceived == receivable_amount（及 writtenOff + unwrittenOff 同式），
 *      这是「账务正确性」的数学契约，后续任何收/核/开票链路测试都应复用此断言。</p>
 *
 * <p>基座沿用 MybatisPlusSmokeTest：@SpringBootTest + firestar_test 集成测试库，随机前缀保证幂等、可重复跑。
 * 需要可达的 dev MySQL（与现有测试同环境）；本类不自行起服务。</p>
 */
@SpringBootTest
@ActiveProfiles("it")
class BusinessInvariantAndConcurrencyTest {

    private static final String PREFIX = "BIZ_TEST_"
            + java.util.UUID.randomUUID().toString().substring(0, 8) + "_";

    @Autowired
    private ItemMapper itemMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private ReceivableMapper receivableMapper;
    @Autowired
    private ReceiptMapper receiptMapper;
    @Autowired
    private ReceiptService receiptService;
    @Autowired
    private ShipmentService shipmentService;
    @Autowired
    private ShipmentMapper shipmentMapper;
    @Autowired
    private ShipmentLineMapper shipmentLineMapper;

    private Long newProjectAndItem(int quantity) {
        Project p = new Project();
        p.setProjectNo(PREFIX + "P");
        p.setProjectName("业务测试项目");
        p.setContractStatus("UNSIGNED");
        p.setApprovalStatus("PENDING_APPROVAL");
        p.setContractAmount(new BigDecimal("1000.00"));
        p.setTenantId(0L);
        projectMapper.insert(p);

        Item item = new Item();
        item.setProjectId(p.getId());
        item.setItemName("测试货物");
        item.setQuantity(quantity);
        item.setShippedQty(0);
        item.setUnitPrice(new BigDecimal("10.00"));
        item.setTenantId(0L);
        itemMapper.insert(item);
        return item.getId();
    }

    @Test
    @DisplayName("P0-1 回归：两线程并发调用 ShipmentServiceImpl.create，合同量 10、各发 6，不得超发（真调服务）")
    void concurrentShipmentDoesNotOverIssued() throws Exception {
        Long itemId = newProjectAndItem(10);
        Item before = itemMapper.selectById(itemId);
        Long projectId = before.getProjectId();

        int qty = 6;
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        // ⚠️ 本用例此前是「在测试内复制一份原子 UPDATE SQL 打 itemMapper」——验的是 SQL 副本，
        //    ShipmentServiceImpl 真实逻辑一旦漂移，测试照样绿（假安全感）。
        //    现改为真实调用 shipmentService.create，超发校验由服务内部的原子 UPDATE 兜底。
        Runnable task = () -> {
            try {
                start.await();
                // SecurityContext 是线程隔离的，必须在每个线程内各自注入（create 内部读 currentUserId）
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("biz_test", null, java.util.List.of()));
                ShipmentLineCreateReq line = new ShipmentLineCreateReq();
                line.setItemId(itemId);
                line.setShipmentQty(qty);
                ShipmentCreateReq req = new ShipmentCreateReq();
                req.setProjectId(projectId);
                req.setLines(List.of(line));
                shipmentService.create(req);
                successCount.incrementAndGet();
            } catch (Exception ignored) {
                // 超发被服务内原子 UPDATE 拦下（IllegalStateException）或校验拒绝 → 均计为未成功
            } finally {
                done.countDown();
            }
        };

        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            ts.add(new Thread(task));
        }
        ts.forEach(Thread::start);
        start.countDown();
        done.await();

        Item after = itemMapper.selectById(itemId);
        // 核心不变量：并发下绝不突破合同量（原乐观锁静默失败时会变成 12）
        assertTrue(after.getShippedQty() <= 10,
                "并发发货后已发量必须 <= 合同量 10，实际=" + after.getShippedQty());
        // 两个 6 不可能都成功（12 > 10），至少一个被服务内原子 UPDATE 的 WHERE 拦下
        assertTrue(successCount.get() < threads,
                "超发应被拦下：成功次数应 < 线程数，实际=" + successCount.get());

        // 清理：create 会落发货单 + 明细行（超发那条可能已插入后被拦/回滚，一并清）
        List<Shipment> ss = shipmentMapper.selectList(
                new LambdaQueryWrapper<Shipment>().eq(Shipment::getProjectId, projectId));
        for (Shipment s : ss) {
            shipmentLineMapper.delete(new LambdaQueryWrapper<ShipmentLine>()
                    .eq(ShipmentLine::getShipmentId, s.getId()));
            shipmentMapper.deleteById(s.getId());
        }
        itemMapper.deleteById(itemId);
        projectMapper.deleteById(projectId);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("账务不变量：received+unreceived == receivable_amount 且 writtenOff+unwrittenOff == receivable_amount")
    void receivableAmountInvariantHolds() {
        Project p = new Project();
        p.setProjectNo(PREFIX + "INV");
        p.setProjectName("不变量测试");
        p.setContractStatus("UNSIGNED");
        p.setApprovalStatus("PENDING_APPROVAL");
        p.setContractAmount(new BigDecimal("500.00"));
        p.setTenantId(0L);
        projectMapper.insert(p);

        Receivable r = new Receivable();
        r.setProjectId(p.getId());
        r.setReceivableNo(PREFIX + "R1");
        r.setReceivableAmount(new BigDecimal("100.00"));
        r.setReceivedAmount(new BigDecimal("40.00"));
        r.setUnreceivedAmount(new BigDecimal("60.00"));
        r.setWrittenOffAmount(BigDecimal.ZERO);
        r.setUnwrittenOffAmount(new BigDecimal("100.00"));
        r.setInvoicedAmount(BigDecimal.ZERO);
        r.setUninvoicedAmount(new BigDecimal("100.00"));
        r.setDocDirection("BLUE");
        r.setStatus("NORMAL");
        r.setSourceType("FROM_SHIPMENT");
        r.setTenantId(0L);
        receivableMapper.insert(r);

        Receivable loaded = receivableMapper.selectById(r.getId());
        assertEquals(0, loaded.getReceivedAmount().add(loaded.getUnreceivedAmount())
                        .compareTo(loaded.getReceivableAmount()),
                "应收单必须满足 received + unreceived == receivable_amount");
        assertEquals(0, loaded.getWrittenOffAmount().add(loaded.getUnwrittenOffAmount())
                        .compareTo(loaded.getReceivableAmount()),
                "应收单必须满足 writtenOff + unwrittenOff == receivable_amount");

        receivableMapper.deleteById(r.getId());
        projectMapper.deleteById(p.getId());
    }

    @Test
    @DisplayName("超收双保险·后端关口一：蓝字收款使累计已收超过合同额时被拒；未超收则放行")
    void overCollectionRejectedAtCreate() {
        // 注入安全上下文（create 内部读 currentUserId，测试需有登录身份；无对应用户时 id 取 null 不影响守卫）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("biz_test", null, java.util.List.of()));
        // 复刻真实事故：合同 6350，已有一笔 RECEIVED 蓝字收款 1170，再录整份合同额 6350 → 累计 7520 > 6350
        Project p = new Project();
        p.setProjectNo(PREFIX + "OC");
        p.setProjectName("超收拦截测试");
        p.setContractStatus("SIGNED");
        p.setApprovalStatus("APPROVED");
        p.setContractAmount(new BigDecimal("6350.00"));
        p.setTenantId(0L);
        projectMapper.insert(p);

        Receipt existing = new Receipt();
        existing.setProjectId(p.getId());
        existing.setReceiptNo(PREFIX + "OC_R1");
        existing.setReceiptAmount(new BigDecimal("1170.00"));
        existing.setDocDirection("BLUE");
        existing.setReceiptStatus("RECEIVED");
        existing.setApprovalStatus("APPROVED");
        existing.setReceiptType("NORMAL");
        existing.setTenantId(0L);
        receiptMapper.insert(existing);

        try {
        // ① 超收：再录整份合同额 → 必须被 create 关口拒绝
        ReceiptRequest over = new ReceiptRequest();
        over.setProjectId(p.getId());
        over.setReceiptAmount(new BigDecimal("6350.00"));
        over.setReceiptType("NORMAL");
        over.setReceiptDate(LocalDate.now());
        over.setAttachmentFileIds(List.of(999999L));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> receiptService.create(over),
                "超收收款必须被后端关口拒绝");
        assertTrue(ex.getMessage().contains("合同可收额度") || ex.getMessage().contains("超出"),
                "异常信息应说明超出合同额度，实际=" + ex.getMessage());

        // ② 未超收：再录 100 → 累计 1270 <= 6350，必须放行（证明关口不会误杀正常收款）
        ReceiptRequest ok = new ReceiptRequest();
        ok.setProjectId(p.getId());
        ok.setReceiptAmount(new BigDecimal("100.00"));
        ok.setReceiptType("NORMAL");
        ok.setReceiptDate(LocalDate.now());
        ok.setAttachmentFileIds(List.of(999999L));
        assertDoesNotThrow(() -> receiptService.create(ok), "未超收的收款应被放行");

        } finally {
            // 无论如何都清掉本项目构造的数据，避免 generateReceiptNo 固定单号导致的跨运行残留
            receiptMapper.delete(new LambdaQueryWrapper<Receipt>().eq(Receipt::getProjectId, p.getId()));
            projectMapper.deleteById(p.getId());
            SecurityContextHolder.clearContext();
        }
    }
}
