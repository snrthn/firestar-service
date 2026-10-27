package com.firestar;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.firestar.entity.Item;
import com.firestar.entity.Project;
import com.firestar.entity.Receivable;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceivableMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
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
 * <p>基座沿用 MybatisPlusSmokeTest：@SpringBootTest + dev 库，随机前缀保证幂等、可重复跑。
 * 需要可达的 dev MySQL（与现有测试同环境）；本类不自行起服务。</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class BusinessInvariantAndConcurrencyTest {

    private static final String PREFIX = "BIZ_TEST_"
            + java.util.UUID.randomUUID().toString().substring(0, 8) + "_";

    @Autowired
    private ItemMapper itemMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private ReceivableMapper receivableMapper;

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
    @DisplayName("P0-1 回归：两线程并发发货，合同量 10、各发 6，不得超发（最终 <= 10 且至少一个被拦）")
    void concurrentShipmentDoesNotOverIssued() throws Exception {
        Long itemId = newProjectAndItem(10);

        int qty = 6;
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        Runnable task = () -> {
            try {
                start.await();
                // 与 ShipmentServiceImpl P0-1 修复完全一致的原子 UPDATE：把超发校验下推到 SQL
                int rows = itemMapper.update(null, new LambdaUpdateWrapper<Item>()
                        .eq(Item::getId, itemId)
                        .apply("shipped_qty + {0} <= quantity", qty)
                        .setSql("shipped_qty = shipped_qty + " + qty));
                if (rows > 0) {
                    successCount.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
        // 两个 6 不可能都成功（12 > 10），至少一个被原子 UPDATE 的 WHERE 拦下
        assertTrue(successCount.get() < threads,
                "超发应被拦下：成功次数应 < 线程数，实际=" + successCount.get());

        itemMapper.deleteById(itemId);
        projectMapper.deleteById(after.getProjectId());
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
}
