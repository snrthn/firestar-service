package com.firestar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.AllocationItem;
import com.firestar.dto.InvoiceRequest;
import com.firestar.entity.Invoice;
import com.firestar.entity.InvoiceAllocation;
import com.firestar.entity.Project;
import com.firestar.entity.Receivable;
import com.firestar.mapper.InvoiceAllocationMapper;
import com.firestar.mapper.InvoiceMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.service.InvoiceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 票款勾稽闸门回归测试（V77 字段 allowInvoiceBeforeReceipt 的消费验证）。
 *
 * <p>背景：立项字段【允许回款前开票】在 InvoiceServiceImpl 有 3 处消费（无分配旧路径 /
 * 逐行分配 / 审批核销生效），但 <b>此前零测试覆盖，且库内 33 个项目全为默认值 1 从未触发</b>——
 * 属"链路通、写了、但完全未经证实"的代码。本类补上这块最短的板。</p>
 *
 * <p>口径（与 InvoiceServiceImpl 严格一致）：
 * 可开票额度 ceiling = 已回款 received − 已开票 invoiced；
 * 仅当项目 allowInvoiceBeforeReceipt = false 时强制（true / null = 放行）；红冲单 RED 一律放行。</p>
 *
 * <p>构造：应收单 1000，已回款 100、已开票 0 → ceiling = 100；未开票余额 1000 充足
 * （保证先过"未开票余额"闸，不会被前面的校验提前拦下，从而精确命中票款勾稽这一段）。</p>
 *
 * <p>基座沿用 BusinessInvariantAndConcurrencyTest：@SpringBootTest + dev 库 + 随机前缀，
 * 需要可达的 dev MySQL；本类不自行起服务。</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class InvoiceReceiptGateTest {

    private static final String PREFIX = "TRG_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    /** 不存在的附件 ID：bindAttachments 内部 selectById 为 null 会 continue，不会抛错 */
    private static final Long DUMMY_FILE_ID = 999999L;

    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private ReceivableMapper receivableMapper;
    @Autowired
    private InvoiceMapper invoiceMapper;
    @Autowired
    private InvoiceAllocationMapper invoiceAllocationMapper;
    @Autowired
    private InvoiceService invoiceService;

    private Project newProject(boolean allowInvoiceBeforeReceipt, String tag) {
        Project p = new Project();
        p.setProjectNo(PREFIX + tag);
        p.setProjectName("票款勾稽测试");
        p.setContractStatus("SIGNED");
        p.setApprovalStatus("APPROVED");
        p.setContractAmount(new BigDecimal("1000.00"));
        p.setAllowInvoiceBeforeReceipt(allowInvoiceBeforeReceipt);
        p.setTenantId(0L);
        projectMapper.insert(p);
        return p;
    }

    private Receivable newReceivable(Long projectId, String tag) {
        Receivable r = new Receivable();
        r.setProjectId(projectId);
        r.setReceivableNo(PREFIX + tag);
        r.setReceivableAmount(new BigDecimal("1000.00"));
        r.setReceivedAmount(new BigDecimal("100.00"));
        r.setUnreceivedAmount(new BigDecimal("900.00"));
        r.setInvoicedAmount(BigDecimal.ZERO);
        r.setUninvoicedAmount(new BigDecimal("1000.00"));
        r.setWrittenOffAmount(BigDecimal.ZERO);
        r.setUnwrittenOffAmount(new BigDecimal("1000.00"));
        r.setDocDirection("BLUE");
        r.setStatus("NORMAL");
        r.setSourceType("FROM_SHIPMENT");
        r.setTenantId(0L);
        receivableMapper.insert(r);
        return r;
    }

    private InvoiceRequest request(Long projectId, Long receivableId, BigDecimal amount, boolean withAllocations) {
        InvoiceRequest req = new InvoiceRequest();
        req.setProjectId(projectId);
        req.setInvoiceAmount(amount);
        req.setInvoiceDate(LocalDate.now());
        req.setInvoiceType("NORMAL");
        req.setTaxRate(new BigDecimal("0.1300"));
        req.setAttachmentFileIds(List.of(DUMMY_FILE_ID));
        if (withAllocations) {
            AllocationItem a = new AllocationItem();
            a.setReceivableId(receivableId);
            a.setAmount(amount);
            req.setAllocations(List.of(a));
        } else {
            req.setReceivableId(receivableId);
        }
        return req;
    }

    private void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("invoice_gate_test", null, List.of()));
    }

    private void cleanup(Long projectId) {
        List<Invoice> invs = invoiceMapper.selectList(
                new LambdaQueryWrapper<Invoice>().eq(Invoice::getProjectId, projectId));
        for (Invoice iv : invs) {
            invoiceAllocationMapper.delete(new LambdaQueryWrapper<InvoiceAllocation>()
                    .eq(InvoiceAllocation::getInvoiceId, iv.getId()));
            invoiceMapper.deleteById(iv.getId());
        }
        receivableMapper.delete(new LambdaQueryWrapper<Receivable>()
                .eq(Receivable::getProjectId, projectId));
        projectMapper.deleteById(projectId);
    }

    @Test
    @DisplayName("票款勾稽·分配行：项目设为不允许回款前开票时，开票额超「已回款−已开票」必须被拒")
    void gateBlocksOnAllocationRowWhenDisallowed() {
        login();
        Project p = newProject(false, "DENY_ALLOC");
        Receivable r = newReceivable(p.getId(), "R_ALLOC");
        try {
            // ceiling = 100 − 0 = 100，本次开 300 → 必须被拒
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> invoiceService.create(request(p.getId(), r.getId(), new BigDecimal("300.00"), true)),
                    "不允许回款前开票的项目，超额开票必须被拒");
            assertTrue(ex.getMessage().contains("不允许回款前开票"),
                    "异常应明确指向票款勾稽闸门，实际=" + ex.getMessage());
        } finally {
            cleanup(p.getId());
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("票款勾稽·开关放行：同一超额开票在 allowInvoiceBeforeReceipt=true 下必须放行（证明不会误杀）")
    void gateAllowsWhenAllowed() {
        login();
        Project p = newProject(true, "ALLOW_ALLOC");
        Receivable r = newReceivable(p.getId(), "R_ALLOW");
        try {
            // 同样的 300 > ceiling 100，但项目允许回款前开票 → 不得拦截
            assertDoesNotThrow(
                    () -> invoiceService.create(request(p.getId(), r.getId(), new BigDecimal("300.00"), true)),
                    "允许回款前开票的项目不应被票款勾稽误杀");
        } finally {
            cleanup(p.getId());
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("票款勾稽·空分配旧兼容路径：无 allocations 时同样必须过闸门（不得绕过）")
    void gateBlocksOnLegacyEmptyAllocationPath() {
        login();
        Project p = newProject(false, "DENY_LEGACY");
        Receivable r = newReceivable(p.getId(), "R_LEGACY");
        try {
            // 旧兼容路径（allocations 为空，走 receivableId 单张）：也必须被勾稽拦下，
            // 否则传空 allocations 就能绕过闸门（历史上 id=7 开出 55,555,555 即此类绕过）
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> invoiceService.create(request(p.getId(), r.getId(), new BigDecimal("300.00"), false)),
                    "空分配路径不得绕过票款勾稽闸门");
            assertTrue(ex.getMessage().contains("不允许回款前开票"),
                    "异常应明确指向票款勾稽闸门，实际=" + ex.getMessage());
        } finally {
            cleanup(p.getId());
            SecurityContextHolder.clearContext();
        }
    }
}
