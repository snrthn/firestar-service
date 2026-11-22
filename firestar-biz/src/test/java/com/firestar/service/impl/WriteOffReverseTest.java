package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.firestar.dto.AutoMatchResultVO;
import com.firestar.dto.ReverseRequest;
import com.firestar.entity.*;
import com.firestar.mapper.*;
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
 * 核销反向操作回归（P1 测试补齐）。
 *
 * <p>既有 {@code WriteOffDualLedgerInvariantTest} 是数据层不变量（直接改库断言守恒），
 * 本类补 Service 层缺口：autoMatch 的贪心配对与跨项目闸门、reverse 的守卫链。
 * 纯 Mockito，不连库、不触发审批流（现有测试库 sys_process_def 为空会让服务级测试卡在流程闸门）。</p>
 */
@ExtendWith(MockitoExtension.class)
class WriteOffReverseTest {

    @Mock private WriteOffMapper writeOffMapper;
    @Mock private InvoiceAllocationMapper invoiceAllocationMapper;
    @Mock private ReceiptMapper receiptMapper;
    @Mock private ReceivableMapper receivableMapper;
    @Mock private InvoiceMapper invoiceMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private UserMapper userMapper;

    @InjectMocks private WriteOffServiceImpl service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        for (Class<?> c : List.of(Receipt.class, Receivable.class, WriteOff.class, User.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), c);
        }
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("cashier", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Receipt receipt(long id, String unwrittenOff) {
        Receipt r = new Receipt();
        r.setId(id);
        r.setProjectId(1L);
        r.setReceiptStatus("RECEIVED");
        r.setUnwrittenOffAmount(new BigDecimal(unwrittenOff));
        return r;
    }

    private Receivable receivable(long id, String unreceived, long applicantId) {
        Receivable r = new Receivable();
        r.setId(id);
        r.setProjectId(1L);
        r.setUnreceivedAmount(new BigDecimal(unreceived));
        r.setApplicantId(applicantId);
        r.setShipperId(applicantId);
        return r;
    }

    @Test
    @DisplayName("跨项目自动核销被拒：projectId 必填，禁止跨项目错配")
    void autoMatch_noProject_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.autoMatch(null));
        assertTrue(ex.getMessage().contains("跨项目"));
        verifyNoInteractions(receiptMapper);
    }

    @Test
    @DisplayName("贪心配对：一笔收款 250 依次吃两张应收（200+50），总匹配额守恒")
    void autoMatch_greedyPairs_conservesAmount() {
        when(receiptMapper.selectList(any())).thenReturn(List.of(receipt(10L, "250.00")));
        when(receivableMapper.selectList(any())).thenReturn(List.of(
                receivable(1L, "200.00", 42L),
                receivable(2L, "100.00", 42L)
        ));

        AutoMatchResultVO res = service.autoMatch(1L);

        assertEquals(2, res.getMatchCount());
        assertEquals(0, new BigDecimal("250.00").compareTo(res.getTotalAmount()),
                "匹配总额必须等于实际核销金额之和，不能虚增");
        verify(writeOffMapper, times(2)).insert(any(WriteOff.class));
    }

    @Test
    @DisplayName("无可配对单据时报清晰错误，不产生空核销")
    void autoMatch_noCandidates_rejected() {
        when(receiptMapper.selectList(any())).thenReturn(List.of());
        when(receivableMapper.selectList(any())).thenReturn(List.of(receivable(1L, "100.00", 42L)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.autoMatch(1L));
        assertTrue(ex.getMessage().contains("没有可配对"));
        verify(writeOffMapper, never()).insert(any(WriteOff.class));
    }

    @Test
    @DisplayName("反核销守卫：缺少 ledgerId / 原因时直接拒绝，不查库")
    void reverse_missingParams_rejected() {
        ReverseRequest noId = new ReverseRequest();
        noId.setReason("测试");
        assertThrows(IllegalArgumentException.class, () -> service.reverse(noId));

        ReverseRequest noReason = new ReverseRequest();
        noReason.setLedgerId(1L);
        assertThrows(IllegalArgumentException.class, () -> service.reverse(noReason));

        verifyNoInteractions(writeOffMapper);
    }

    @Test
    @DisplayName("反核销守卫：仅 NORMAL 状态可反，REVERSED 二次反冲被拒")
    void reverse_alreadyReversed_rejected() {
        WriteOff w = new WriteOff();
        w.setId(5L);
        w.setReceivableId(1L);
        w.setStatus("REVERSED");
        when(writeOffMapper.selectById(5L)).thenReturn(w);

        ReverseRequest req = new ReverseRequest();
        req.setLedgerId(5L);
        req.setLedgerType("RECEIPT");
        req.setReason("重复反冲");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.reverse(req));
        assertTrue(ex.getMessage().contains("仅 NORMAL"));
    }

    @Test
    @DisplayName("反核销守卫：ledgerType 非法直接拒绝")
    void reverse_invalidLedgerType_rejected() {
        ReverseRequest req = new ReverseRequest();
        req.setLedgerId(6L);
        req.setLedgerType("UNKNOWN");
        req.setReason("非法类型");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.reverse(req));
        assertTrue(ex.getMessage().contains("ledgerType"));
    }
}
