package com.firestar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.entity.InvoiceAllocation;
import com.firestar.entity.Project;
import com.firestar.entity.Receivable;
import com.firestar.entity.WriteOff;
import com.firestar.mapper.InvoiceAllocationMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.WriteOffMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核销「双台账」不变量回归测试。
 *
 * <p>本项目把核销拆成两本互不覆盖的账（2026-10-27 虎哥确认的业务前提）：
 * <ul>
 *   <li><b>钱的账</b>：{@code received / unreceived}，由回款核销 {@code fin_write_off} 驱动；</li>
 *   <li><b>票的账</b>：{@code invoiced / uninvoiced}，由开票核销 {@code fin_invoice_allocation} 驱动。</li>
 * </ul>
 * 两本账各自独立守恒：<br>
 * {@code received + unreceived == receivable_amount} 且 {@code invoiced + uninvoiced == receivable_amount}。<br>
 * 且<b>额度互不占用</b>：钱收齐了不代表票开完，票开完了也不代表款收齐——
 * 若二者互相扣减，则「款已收讫但票未开完」这类真实业务状态无法表达。</p>
 *
 * <p>⚠️ 覆盖层级说明：本类为<b>数据层不变量</b>回归（与既有 receivableAmountInvariantHolds 同范式）。
 * 服务级（走审批通过生效核销）验证当前受阻于 firestar_test 集成测试库 {@code sys_process_def} 为空——
 * 退货/开票等审批流程未配置，服务内会先抛「流程未配置」而到不了核销逻辑。
 * 待流程配置就绪后可升级为服务级用例。</p>
 *
 * <p>基座：@SpringBootTest + firestar_test 集成测试库 + 随机前缀，需要可达的 dev MySQL；本类不自行起服务。</p>
 */
@SpringBootTest
@ActiveProfiles("it")
class WriteOffDualLedgerInvariantTest {

    private static final String PREFIX = "DLG_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private ReceivableMapper receivableMapper;
    @Autowired
    private WriteOffMapper writeOffMapper;
    @Autowired
    private InvoiceAllocationMapper invoiceAllocationMapper;

    private Project newProject(String tag) {
        Project p = new Project();
        p.setProjectNo(PREFIX + tag);
        p.setProjectName("双台账不变量测试");
        p.setContractStatus("SIGNED");
        p.setApprovalStatus("APPROVED");
        p.setContractAmount(new BigDecimal("1000.00"));
        p.setTenantId(0L);
        projectMapper.insert(p);
        return p;
    }

    private Receivable newReceivable(Long projectId, String tag) {
        Receivable r = new Receivable();
        r.setProjectId(projectId);
        r.setReceivableNo(PREFIX + tag);
        r.setReceivableAmount(new BigDecimal("1000.00"));
        r.setReceivedAmount(BigDecimal.ZERO);
        r.setUnreceivedAmount(new BigDecimal("1000.00"));
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

    private void cleanup(Long projectId) {
        receivableMapper.delete(new LambdaQueryWrapper<Receivable>()
                .eq(Receivable::getProjectId, projectId));
        projectMapper.deleteById(projectId);
    }

    @Test
    @DisplayName("双台账不变量：钱/票两对各自守恒，且额度互不占用（款收齐仍可全额开票，反之亦然）")
    void dualLedgerIsIndependentAndConserved() {
        Project p = newProject("P");
        Receivable r = newReceivable(p.getId(), "R");
        try {
            // ① 初始：两对不变量各自成立
            assertLedgerConserved(r.getId(), "初始");

            // ② 钱的账核销到满（款收齐）：received=1000 / unreceived=0，并落一条 fin_write_off
            WriteOff wo = new WriteOff();
            wo.setWriteOffNo(PREFIX + "WO");
            wo.setReceivableId(r.getId());
            wo.setAmount(new BigDecimal("1000.00"));
            wo.setWriteOffDate(LocalDate.now());
            // fin_write_off.receipt_id 是 NOT NULL 且无外键；双台账不变量不读该列，填占位即可
            wo.setReceiptId(0L);
            wo.setWriteOffMethod("AUTO");
            wo.setStatus("APPROVED");
            writeOffMapper.insert(wo);

            Receivable mid = receivableMapper.selectById(r.getId());
            mid.setReceivedAmount(new BigDecimal("1000.00"));
            mid.setUnreceivedAmount(BigDecimal.ZERO);
            receivableMapper.updateById(mid);

            // 关键：款已收齐，票的可开额度必须仍是全额（钱的核销不占用票的额度）
            Receivable afterMoney = receivableMapper.selectById(r.getId());
            assertEquals(0, afterMoney.getUninvoicedAmount().compareTo(new BigDecimal("1000.00")),
                    "款收齐后票的可开额度仍须为全额（双台账互不占用），实际=" + afterMoney.getUninvoicedAmount());
            assertLedgerConserved(r.getId(), "款收齐后");

            // ③ 票的账核销到满（票开完）：invoiced=1000 / uninvoiced=0，并落一条 fin_invoice_allocation
            InvoiceAllocation ia = new InvoiceAllocation();
            ia.setAllocationNo(PREFIX + "IA");
            ia.setReceivableId(r.getId());
            ia.setAmount(new BigDecimal("1000.00"));
            // fin_invoice_allocation.invoice_id 同上
            ia.setInvoiceId(0L);
            ia.setStatus("APPROVED");
            invoiceAllocationMapper.insert(ia);

            Receivable m2 = receivableMapper.selectById(r.getId());
            m2.setInvoicedAmount(new BigDecimal("1000.00"));
            m2.setUninvoicedAmount(BigDecimal.ZERO);
            receivableMapper.updateById(m2);

            // 关键：票已开完，钱的已收额必须仍是全额（票的核销不占用钱的额度）
            Receivable afterInvoice = receivableMapper.selectById(r.getId());
            assertEquals(0, afterInvoice.getReceivedAmount().compareTo(new BigDecimal("1000.00")),
                    "票开完后已回款额仍须为全额（双台账互不占用），实际=" + afterInvoice.getReceivedAmount());
            assertLedgerConserved(r.getId(), "票开完后");

            writeOffMapper.deleteById(wo.getId());
            invoiceAllocationMapper.deleteById(ia.getId());
        } finally {
            cleanup(p.getId());
        }
    }

    /** 两本账各自守恒：received+unreceived == 且 invoiced+uninvoiced == receivable_amount */
    private void assertLedgerConserved(Long receivableId, String stage) {
        Receivable x = receivableMapper.selectById(receivableId);
        BigDecimal amount = x.getReceivableAmount();
        assertEquals(0, x.getReceivedAmount().add(x.getUnreceivedAmount()).compareTo(amount),
                stage + "：钱的账必须守恒 received + unreceived == receivable_amount，实际 received="
                        + x.getReceivedAmount() + " unreceived=" + x.getUnreceivedAmount());
        assertEquals(0, x.getInvoicedAmount().add(x.getUninvoicedAmount()).compareTo(amount),
                stage + "：票的账必须守恒 invoiced + uninvoiced == receivable_amount，实际 invoiced="
                        + x.getInvoicedAmount() + " uninvoiced=" + x.getUninvoicedAmount());
    }
}
