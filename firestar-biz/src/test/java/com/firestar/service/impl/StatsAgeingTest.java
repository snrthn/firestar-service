package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.firestar.dto.AgeingSegmentVO;
import com.firestar.dto.AgeingVO;
import com.firestar.dto.StatsQuery;
import com.firestar.entity.*;
import com.firestar.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 看板账龄（L2 FIFO）纯单测（P1 测试补齐）。
 *
 * <p>{@code StatsServiceImpl.ageing} 此前零覆盖。这里用 Mockito 隔离 13 个 mapper，
 * 只喂应收队列 + 核销流水，验证核心业务规则：
 * ① FIFO 回款先冲最老应收；② 五段账龄分桶边界（0/30/60/90 天）；
 * ③ 红字应收取负不进未收；④ 退款类核销不参与冲抵。</p>
 */
@ExtendWith(MockitoExtension.class)
class StatsAgeingTest {

    @Mock private ProjectMapper projectMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private UserMapper userMapper;
    @Mock private ReceivableMapper receivableMapper;
    @Mock private ReceiptMapper receiptMapper;
    @Mock private InvoiceMapper invoiceMapper;
    @Mock private ShipmentMapper shipmentMapper;
    @Mock private ShipmentLineMapper shipmentLineMapper;
    @Mock private WriteOffMapper writeOffMapper;
    @Mock private ReturnOrderMapper returnOrderMapper;
    @Mock private ItemMapper itemMapper;
    @Mock private LoginLogMapper loginLogMapper;

    @InjectMocks private StatsServiceImpl service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), Project.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), Receivable.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), WriteOff.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), Receipt.class);
    }

    private Receivable receivable(long id, String amount, int daysAgo, String dir) {
        Receivable r = new Receivable();
        r.setId(id);
        r.setProjectId(1L);
        r.setReceivableAmount(new BigDecimal(amount));
        r.setReceivableDate(LocalDate.now().minusDays(daysAgo));
        r.setDocDirection(dir);
        return r;
    }

    private WriteOff writeOff(long id, long receivableId, long receiptId, String amount, int daysAgo) {
        WriteOff w = new WriteOff();
        w.setId(id);
        w.setReceivableId(receivableId);
        w.setReceiptId(receiptId);
        w.setAmount(new BigDecimal(amount));
        w.setWriteOffDate(LocalDate.now().minusDays(daysAgo));
        w.setStatus("NORMAL");
        return w;
    }

    @Test
    @DisplayName("FIFO：回款 400 冲最老应收（100 天），其余三段余额按账龄落桶")
    void ageing_fifo_consumesOldestFirst() {
        // 无 projectId 过滤 → resolveProjectIds 走 selectList 拉全部项目
        when(receivableMapper.selectList(any())).thenReturn(List.of(
                receivable(1L, "100.00", 100, "BLUE"), // 90天以上
                receivable(2L, "200.00", 45, "BLUE"),  // 31-60天
                receivable(3L, "300.00", 15, "BLUE"),  // 0-30天
                receivable(4L, "50.00", 0, "BLUE")     // 未到期
        ));
        when(writeOffMapper.selectList(any())).thenReturn(List.of(
                writeOff(1L, 1L, 10L, "400.00", 5) // 超过最老单 100 → 溢出冲第二老单 300
        ));
        Receipt normal = new Receipt();
        normal.setId(10L);
        normal.setReceiptType("NORMAL");
        when(receiptMapper.selectBatchIds(any())).thenReturn(List.of(normal));

        AgeingVO vo = service.ageing(new StatsQuery());

        assertEquals("FIFO", vo.getMethod());
        // 最老单 100 全冲掉；第二老单 200 被冲 300 → 全清；第三单 300 被冲 0（400-100-200=100 实际冲了第三单100）
        // 重算：400 - 100(单1) = 300；300 - 200(单2) = 100；100 - 300(单3) → 冲100，剩200
        assertEquals(new BigDecimal("250.00"), vo.getTotalUnreceived(),
                "剩余 = 单3 的 200 + 单4 的 50");
        List<AgeingSegmentVO> seg = vo.getSegments();
        assertEquals(5, seg.size());
        assertEquals("未到期", seg.get(0).getLabel());
        assertEquals(0, new BigDecimal("50.00").compareTo(seg.get(0).getAmount()));
        assertEquals("0-30天", seg.get(1).getLabel());
        assertEquals(0, new BigDecimal("200.00").compareTo(seg.get(1).getAmount()));
        assertEquals(BigDecimal.ZERO, seg.get(2).getAmount(), "31-60 天已被 FIFO 冲平");
        assertEquals(BigDecimal.ZERO, seg.get(4).getAmount(), "90天以上已被 FIFO 冲平");
        assertEquals(1, vo.getOverdueCount(), "仅 15 天那笔算逾期；未到期与已冲平的不计");
        assertTrue(vo.getOverdueAmount().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    @DisplayName("红字应收取负不进未收：蓝 100 + 红 100 → 总额 0")
    void ageing_redReceivable_excluded() {
        when(receivableMapper.selectList(any())).thenReturn(List.of(
                receivable(1L, "100.00", 40, "BLUE"),
                receivable(2L, "100.00", 40, "RED")
        ));
        when(writeOffMapper.selectList(any())).thenReturn(List.of());

        AgeingVO vo = service.ageing(new StatsQuery());

        assertEquals(new BigDecimal("100.00"), vo.getTotalUnreceived(), "红字单取负后不产生未收，蓝字单仍计入");
        assertEquals(1, vo.getOverdueCount(), "蓝字单 40 天 → 1 笔逾期；红字单不计数");
        assertEquals(0, BigDecimal.ONE.compareTo(vo.getOverdueRate()), "全部未收均逾期");
    }

    @Test
    @DisplayName("退款类核销不参与冲抵：REFUND 收款对应的 WriteOff 被排除")
    void ageing_refundWriteOff_excluded() {
        when(receivableMapper.selectList(any())).thenReturn(List.of(
                receivable(1L, "100.00", 10, "BLUE")
        ));
        when(writeOffMapper.selectList(any())).thenReturn(List.of(
                writeOff(1L, 1L, 99L, "100.00", 5)
        ));
        Receipt refund = new Receipt();
        refund.setId(99L);
        refund.setReceiptType("REFUND");
        when(receiptMapper.selectBatchIds(any())).thenReturn(List.of(refund));

        AgeingVO vo = service.ageing(new StatsQuery());

        assertEquals(new BigDecimal("100.00"), vo.getTotalUnreceived(),
                "退款核销不得冲减应收");
    }

    @Test
    @DisplayName("账龄分桶边界：30 天落 0-30 段，31 天落 31-60 段")
    void ageing_bucketBoundaries() {
        when(receivableMapper.selectList(any())).thenReturn(List.of(
                receivable(1L, "10.00", 30, "BLUE"),
                receivable(2L, "20.00", 31, "BLUE"),
                receivable(3L, "40.00", 60, "BLUE"),
                receivable(4L, "80.00", 61, "BLUE"),
                receivable(5L, "160.00", 91, "BLUE")
        ));
        when(writeOffMapper.selectList(any())).thenReturn(List.of());

        AgeingVO vo = service.ageing(new StatsQuery());
        List<AgeingSegmentVO> seg = vo.getSegments();

        assertEquals(0, new BigDecimal("10.00").compareTo(seg.get(1).getAmount()), "30 天在 0-30 段");
        assertEquals(0, new BigDecimal("60.00").compareTo(seg.get(2).getAmount()), "31-60 段 = 20+40");
        assertEquals(0, new BigDecimal("80.00").compareTo(seg.get(3).getAmount()), "61 天在 61-90 段");
        assertEquals(0, new BigDecimal("160.00").compareTo(seg.get(4).getAmount()), "91 天在 90+ 段");
    }
}
