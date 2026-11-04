package com.firestar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.*;
import com.firestar.entity.Item;
import com.firestar.entity.Project;
import com.firestar.entity.ShipmentLine;
import com.firestar.entity.User;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ShipmentLineMapper;
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
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.context.ActiveProfiles;

/**
 * P1：财务核心域「正向成功路径」冒烟（之前只有不变量/边界测试，缺主路径）。
 * 用 @Transactional 回滚，不污染 firestar_test 集成测试库。
 * 覆盖：发货/收款/开票 create 成功；退货、应收依赖先建发货单（数据链）；核销台账只读。
 *
 * 说明：业务 Service 通过 currentUserId() 读取「当前登录用户」解析操作人，
 * 本测试绕过 Controller 直接调 Service，故需在 setUp 注入一条已存在用户并写入
 * SecurityContext，使其能被 currentUserId() 解析（与各 Service 实现机制一致）。
 */
@SpringBootTest
@ActiveProfiles("it")
@Transactional
class FinanceHappyPathTest {

    @Autowired private ProjectMapper projectMapper;
    @Autowired private ShipmentLineMapper shipmentLineMapper;
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
        // 发货/收款会校验累计金额不得超过合同额；DB 默认 0.00 会让正向路径全部被拦
        p.setContractAmount(new BigDecimal("100000.00"));
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

    /**
     * 收货确认：应收单金额 = Σ 发货明细行 receivedQty × 单价（见 ReceivableServiceImpl
     * cumulativeReceivedAmount）。草稿行 receivedQty 为 0，不确认收货就开票会被
     * 「开票金额超出应收单未开票余额」拦下 —— 这是业务设计，不是可以绕过的校验。
     */
    private void confirmReceived(Long shipmentId) {
        ShipmentLine line = shipmentLineMapper.selectOne(new LambdaQueryWrapper<ShipmentLine>()
                .eq(ShipmentLine::getShipmentId, shipmentId).last("LIMIT 1"));
        assertNotNull(line, "发货单应有明细行");
        line.setReceivedQty(10);
        shipmentLineMapper.updateById(line);
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
        r.setReceiptType("NORMAL");
        r.setReceiptDate(LocalDate.now());
        // create 要求附件列表非空；bindAttachments 对不存在的 fileId 直接跳过，不写脏数据
        r.setAttachmentFileIds(List.of(999999L));
        assertNotNull(receiptService.create(r));
    }

    @Test @DisplayName("P1-财务：createInvoice 成功返回 id（依赖应收单）")
    void createInvoice_success() {
        Long sid = shipmentService.create(buildShipmentReq());
        confirmReceived(sid);
        Long rid = receivableService.createFromShipment(sid, 1L);
        InvoiceRequest r = new InvoiceRequest();
        r.setProjectId(projectId);
        r.setReceivableId(rid);
        r.setInvoiceAmount(new BigDecimal("50.00"));
        r.setInvoiceType("NORMAL"); // invoice_type 字典值（V2 种子：NORMAL / SPECIAL）
        assertNotNull(invoiceService.create(r));
    }

    @Test @DisplayName("P1-财务：createReturn 前置闸——退货明细必填（依赖真实发货单）")
    void createReturn_requiresLines() {
        Long sid = shipmentService.create(buildShipmentReq());
        ReturnCreateReq r = new ReturnCreateReq();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> returnService.create(sid, r));
        assertTrue(ex.getMessage().contains("退货明细不能为空"), "实际=" + ex.getMessage());
        // 完整正向链还需：发货单 APPROVED + 明细行已收数量 + Flowable 流程实例启动 + 通知模板，
        // 属于独立的流程集成测试范畴，不在本「财务正向冒烟」类里造（会引入引擎部署依赖）。
        // 这里钉死的是 Service 的第一道入参闸，确保数据链建起来后校验顺序不被悄悄改掉。
    }

    @Test @DisplayName("P1-财务：createReceivable fromShipment 依赖发货单（数据链冒烟）")
    void createReceivable_fromShipment() {
        Long sid = shipmentService.create(buildShipmentReq());
        confirmReceived(sid);
        assertNotNull(receivableService.createFromShipment(sid, 1L));
    }

    @Test @DisplayName("P1-财务：核销台账分页可读（只读，验证查询链路）")
    void writeOff_ledger_readonly() {
        assertNotNull(writeOffService.pageLedger(1, 10, projectId, null, null, null, null, null));
    }
}
