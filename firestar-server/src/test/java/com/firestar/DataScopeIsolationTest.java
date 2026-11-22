package com.firestar;

import com.firestar.ai.core.CozePluginVerifier;
import com.firestar.ai.entity.AiConversation;
import com.firestar.ai.mapper.AiConversationMapper;
import com.firestar.ai.service.AiChatService;
import com.firestar.dto.ReverseRequest;
import com.firestar.entity.Project;
import com.firestar.entity.Receivable;
import com.firestar.entity.User;
import com.firestar.entity.WriteOff;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.WriteOffMapper;
import com.firestar.service.ReceivableService;
import com.firestar.service.WriteOffService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据范围隔离与签名重放回归（checkList.md 任务 1，P1 欠债补齐）。
 *
 * <p>四条用例全部是「越权/重放必须被拒」的负向断言：
 * 前端按钮隐藏只是 UX，服务端归属校验才是真防线。任何一条失守都等于
 * 「改个 ID 就能看/改别人的单据」。</p>
 *
 * <p>基座沿用 {@link InvoiceReceiptGateTest}：{@code @SpringBootTest} + it profile + firestar_test 库，
 * 随机前缀造数；Service 层断言异常，绕开 {@code @RequirePerm} 的 DB 权限数据依赖。</p>
 */
@SpringBootTest
@ActiveProfiles("it")
class DataScopeIsolationTest {

    private static final String PREFIX = "DSI_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    @Autowired private UserMapper userMapper;
    @Autowired private AiConversationMapper conversationMapper;
    @Autowired private ProjectMapper projectMapper;
    @Autowired private ReceivableMapper receivableMapper;
    @Autowired private WriteOffMapper writeOffMapper;
    @Autowired private AiChatService aiChatService;
    @Autowired private ReceivableService receivableService;
    @Autowired private WriteOffService writeOffService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private User createUser(String suffix) {
        User u = new User();
        u.setUsername(PREFIX + suffix);
        u.setPassword("$2a$10$dummyHashNotUsedInThisTest");
        u.setNickname("dsi-" + suffix);
        u.setStatus("ENABLED");
        userMapper.insert(u);
        return u;
    }

    private void loginAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u.getUsername(), null, List.of()));
    }

    private Project createProject(User owner) {
        Project p = new Project();
        p.setProjectName(PREFIX + "proj_" + System.nanoTime());
        p.setContractAmount(new BigDecimal("1000.00"));
        p.setSalesManagerId(owner.getId());
        p.setApplicantUsername(owner.getUsername());
        projectMapper.insert(p);
        return p;
    }

    @Test
    @DisplayName("AI 草稿越权：A 以 B 的会话 id 查草稿必须 404（与会话不存在不可区分）")
    void aiDraft_crossUser_returnsNull() {
        User a = createUser("a");
        User b = createUser("b");
        AiConversation conv = new AiConversation();
        conv.setUserId(b.getId());
        conv.setTitle(PREFIX + "conv");
        conv.setProvider("coze"); // DDL: NOT NULL，无默认值
        conversationMapper.insert(conv);

        loginAs(a);
        // 关键：currentUserId 必须是越权方 A。传 B 的 id 属于合法访问，测不到防线。
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> aiChatService.getLatestDraft(conv.getId(), a.getId()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode(), "越权会话必须 404，不得泄露草稿存在性");
    }

    @Test
    @DisplayName("应收详情越权：非申请人/发货人以 id 直取必须 404")
    void receivableDetail_crossUser_throws404() {
        User owner = createUser("owner");
        User stranger = createUser("stranger");
        Project p = createProject(owner);
        Receivable r = new Receivable();
        r.setProjectId(p.getId());
        r.setReceivableNo(PREFIX + "RCV");
        r.setReceivableAmount(new BigDecimal("100.00"));
        r.setApplicantId(owner.getId());
        r.setShipperId(owner.getId());
        receivableMapper.insert(r);

        loginAs(stranger);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> receivableService.detail(r.getId()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode(), "越权详情必须 404，不能 403 泄露资源存在性");
    }

    @Test
    @DisplayName("反核销越权：他人应收对应的核销台账，反核销必须 404")
    void reverseWriteOff_crossUser_throws404() {
        User owner = createUser("owner2");
        User stranger = createUser("stranger2");
        Project p = createProject(owner);
        Receivable r = new Receivable();
        r.setProjectId(p.getId());
        r.setReceivableNo(PREFIX + "RCV2");
        r.setReceivableAmount(new BigDecimal("100.00"));
        r.setApplicantId(owner.getId());
        r.setShipperId(owner.getId());
        receivableMapper.insert(r);

        WriteOff w = new WriteOff();
        w.setWriteOffNo(PREFIX + "WO2"); // DDL: NOT NULL + 租户内唯一
        w.setReceivableId(r.getId());
        w.setReceiptId(999999L); // DDL: NOT NULL；守卫只读 receivable，无需真实收款行
        w.setAmount(new BigDecimal("50.00"));
        w.setWriteOffMethod("RECEIPT");
        w.setStatus("NORMAL");
        writeOffMapper.insert(w);

        ReverseRequest req = new ReverseRequest();
        req.setLedgerId(w.getId());
        req.setLedgerType("RECEIPT");
        req.setReason(PREFIX + "越权测试");

        loginAs(stranger);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> writeOffService.reverse(req));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode(), "越权反核销必须 404");
    }

    @Test
    @DisplayName("扣子插件签名 nonce 重放：同一 nonce 第二次 verify 必须 false")
    void cozePlugin_nonceReplay_rejected() throws Exception {
        String secret = "ut-secret-" + PREFIX;
        CozePluginVerifier verifier = new CozePluginVerifier(secret);
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = PREFIX + "nonce";
        String body = "{\"test\":\"replay\"}";
        String signature = hmacSha256(secret, timestamp + "\n" + nonce + "\n" + body);

        assertTrue(verifier.verify(signature, timestamp, nonce, body), "首次合法签名必须通过");
        assertFalse(verifier.verify(signature, timestamp, nonce, body), "nonce 重放必须被拒");
    }

    private static String hmacSha256(String secret, String payload)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        // 与 CozePluginVerifier#hmacSha256 一致：HexFormat 小写十六进制，不是 Base64
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
