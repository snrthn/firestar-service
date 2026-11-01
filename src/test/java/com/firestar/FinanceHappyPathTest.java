package com.firestar;

import com.firestar.dto.*;
import com.firestar.entity.Item;
import com.firestar.entity.Project;
import com.firestar.entity.User;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1：财务核心域「正向成功路径」冒烟（之前只有不变量/边界测试，缺主路径）。
 * 用 @Transactional 回滚，不污染 dev 库。
 * 覆盖：发货/收款/开票 create 成功；退货、应收依赖先建发货单（数据链）；核销台账只读。
 *
 * 说明：业务 Service 通过 currentUserId() 读取「当前登录用户」解析操作人，
 * 本测试绕过 Controller 直接调 Service，故需在 setUp 注入一条已存在用户并写入
 * SecurityContext，使其能被 currentUserId() 解析（与各 Service 实现机制一致）。
 */
@SpringBootTest
@Transactional
class FinanceHappyPathTest {

    @Autowired private ProjectMapper projectMapper;
    @Autowired private ItemMapper itemMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private ShipmentService shipmentService;
    @Autowired private ReceiptService receiptService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private ReturnService returnService;
    @Autowired private ReceivableService receivableService;
    @Autowired private WriteOffService writeOffService;

    private Long projectId;
    private Long itemId;
    private Long userId;

    @BeforeEach
    void setUp() {
        Project p = new Project();
        p.setProjectName("ut_" + System.nanoTime());
        projectMapper.insert(p);
        projectId = p.getId();

        // 发货明细需要一条真实存在的货物（fin_item），否则 create 会报「货物不存在」
        Item it = new Item();
        it.setProjectId(projectId);
        it.setItemName("ut_item_" + System.nanoTime());
        it.setQuantity(100);
        it.setUnitPrice(new BigDecimal("10"));
        it.setTotalAmount(new BigDecimal("1000"));
        itemMapper.insert(it);
        itemId = it.getId();

        // 注入一条已存在用户并写入 SecurityContext，供 currentUserId() 解析
        User u = new User();
        u.setUsername("fin_flow_" + System.nanoTime());
        u.setPassword(new BCryptPasswordEncoder().encode("Fin@123456"));
        u.setNickname("fin-flow");
        u.setStatus("ENABLED");
        userMapper.insert(u);
        userId = u.getId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u.getUsername(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (userId != null) {
            userMapper.deleteById(userId);
        }
    }

    /** 构造一条合法发货申请（含明细行，引用本测试注入的货物） */
    private ShipmentCreateReq buildShipmentReq() {
        ShipmentLineCreateReq line = new ShipmentLineCreateReq();
        line.setItemId(itemId);
        line.setShipmentQty(10);
        ShipmentCreateReq r = new ShipmentCreateReq();
        r.setProjectId(projectId);
        r.setLines(List.of(line));
        return r;
    }

    @Test @DisplayName("P1-财务：createShipment 成功返回 id")
    void createShipment_success() {
        assertNotNull(shipmentService.create(buildShipmentReq()));
    }

    @Test @DisplayName("P1-财务：createReceipt 成功返回 id")
    void createReceipt_success() {
        ReceiptRequest r = new ReceiptRequest();
        r.setProjectId(projectId);
        r.setReceiptAmount(new BigDecimal("100.00"));
        assertNotNull(receiptService.create(r));
    }

    @Test @DisplayName("P1-财务：createInvoice 成功返回 id（依赖应收单）")
    void createInvoice_success() {
        Long sid = shipmentService.create(buildShipmentReq());
        Long rid = receivableService.createFromShipment(sid, 1L);
        InvoiceRequest r = new InvoiceRequest();
        r.setProjectId(projectId);
        r.setReceivableId(rid);
        r.setInvoiceAmount(new BigDecimal("50.00"));
        assertNotNull(invoiceService.create(r));
    }

    @Test @DisplayName("P1-财务：createReturn 依赖先建发货单（数据链冒烟）")
    void createReturn_dependsOnShipment() {
        Long sid = shipmentService.create(buildShipmentReq());
        ReturnCreateReq r = new ReturnCreateReq();
        assertNotNull(returnService.create(sid, r));
    }

    @Test @DisplayName("P1-财务：createReceivable fromShipment 依赖发货单（数据链冒烟）")
    void createReceivable_fromShipment() {
        Long sid = shipmentService.create(buildShipmentReq());
        assertNotNull(receivableService.createFromShipment(sid, 1L));
    }

    @Test @DisplayName("P1-财务：核销台账分页可读（只读，验证查询链路）")
    void writeOff_ledger_readonly() {
        assertNotNull(writeOffService.pageLedger(1, 10, projectId, null, null, null, null, null));
    }
}
