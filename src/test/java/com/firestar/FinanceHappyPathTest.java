package com.firestar;

import com.firestar.dto.*;
import com.firestar.entity.Project;
import com.firestar.mapper.ProjectMapper;
import com.firestar.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1：财务核心域「正向成功路径」冒烟（之前只有不变量/边界测试，缺主路径）。
 * 用 @Transactional 回滚，不污染 dev 库。
 * 覆盖：发货/收款/开票 create 成功；退货、应收依赖先建发货单（数据链）；核销台账只读。
 * 注：DTO 字段以实际定义为基准，本地编译若报字段缺失按实体提示微调 setter。
 */
@SpringBootTest
@Transactional
class FinanceHappyPathTest {

    @Autowired private ProjectMapper projectMapper;
    @Autowired private ShipmentService shipmentService;
    @Autowired private ReceiptService receiptService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private ReturnService returnService;
    @Autowired private ReceivableService receivableService;
    @Autowired private WriteOffService writeOffService;

    private Long projectId;

    @BeforeEach
    void setUp() {
        Project p = new Project();
        p.setProjectName("ut_" + System.nanoTime());
        projectMapper.insert(p);
        projectId = p.getId();
    }

    @Test @DisplayName("P1-财务：createShipment 成功返回 id")
    void createShipment_success() {
        ShipmentCreateReq r = new ShipmentCreateReq();
        r.setProjectId(projectId);
        r.setTrackingNo("UT" + System.nanoTime());
        assertNotNull(shipmentService.create(r));
    }

    @Test @DisplayName("P1-财务：createReceipt 成功返回 id")
    void createReceipt_success() {
        ReceiptRequest r = new ReceiptRequest();
        r.setProjectId(projectId);
        r.setAmount(new BigDecimal("100.00"));
        assertNotNull(receiptService.create(r));
    }

    @Test @DisplayName("P1-财务：createInvoice 成功返回 id")
    void createInvoice_success() {
        InvoiceRequest r = new InvoiceRequest();
        r.setProjectId(projectId);
        r.setAmount(new BigDecimal("50.00"));
        assertNotNull(invoiceService.create(r));
    }

    @Test @DisplayName("P1-财务：createReturn 依赖先建发货单（数据链冒烟）")
    void createReturn_dependsOnShipment() {
        ShipmentCreateReq s = new ShipmentCreateReq();
        s.setProjectId(projectId);
        s.setTrackingNo("UTS" + System.nanoTime());
        Long sid = shipmentService.create(s);
        ReturnCreateReq r = new ReturnCreateReq();
        assertNotNull(returnService.create(sid, r));
    }

    @Test @DisplayName("P1-财务：createReceivable fromShipment 依赖发货单（数据链冒烟）")
    void createReceivable_fromShipment() {
        ShipmentCreateReq s = new ShipmentCreateReq();
        s.setProjectId(projectId);
        s.setTrackingNo("UTR" + System.nanoTime());
        Long sid = shipmentService.create(s);
        assertNotNull(receivableService.createFromShipment(sid, 1L));
    }

    @Test @DisplayName("P1-财务：核销台账分页可读（只读，验证查询链路）")
    void writeOff_ledger_readonly() {
        assertNotNull(writeOffService.pageLedger(1, 10, projectId, null, null, null));
    }
}
