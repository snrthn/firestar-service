package com.firestar.ai.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出域脱敏器单测：固化字段级脱敏行为，防止漏配/改错规则而无人察觉。
 *
 * <p>脱敏规则由 AiProperties.ai.egress.rules 驱动（配置即行为），
 * 这里手工构造与 application-*.properties 一致的规则集来验证：
 * 严格档字段（税号/银行账号/身份证/客户名 + 本轮新增手机/邮箱/IP）按预期脱敏，
 * 放行字段（金额/人名）保持原值，非 JSON 文本不处理。</p>
 */
class EgressMaskerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EgressMasker masker;

    @BeforeEach
    void setUp() {
        AiProperties props = new AiProperties();
        props.setEgressMode("strict");
        List<AiProperties.EgressRule> rules = new ArrayList<>();
        rules.add(rule("taxNo", "PSEUDONYMIZE"));
        rules.add(rule("bankAccount", "PARTIAL_MASK"));
        rules.add(rule("idCard", "REDACT"));
        rules.add(rule("customerName", "PSEUDONYMIZE"));
        rules.add(rule("amount", "PASS"));
        // 本轮新增第一档 PII
        rules.add(rule("phone", "PARTIAL_MASK"));
        rules.add(rule("email", "PARTIAL_MASK"));
        rules.add(rule("ip", "PARTIAL_MASK"));
        rules.add(rule("ipAddress", "PARTIAL_MASK")); // 日志 VO 实际字段名
        props.setEgressRules(rules);
        masker = new EgressMasker(objectMapper, props);
    }

    private AiProperties.EgressRule rule(String field, String action) {
        AiProperties.EgressRule r = new AiProperties.EgressRule();
        r.setField(field);
        r.setAction(action);
        return r;
    }

    @Test
    void masksConfiguredPiiFields() throws Exception {
        String in = "{\"taxNo\":\"91110000MA12345678\",\"bankAccount\":\"6222021234567890123\","
                + "\"idCard\":\"110101199001011234\",\"phone\":\"13800138000\","
                + "\"email\":\"zhangsan@example.com\",\"ip\":\"192.168.110.68\","
                + "\"ipAddress\":\"10.20.30.40\"}";
        JsonNode out = objectMapper.readTree(masker.mask(in));

        assertTrue(out.get("taxNo").asText().startsWith("TKN_"));
        assertTrue(out.get("bankAccount").asText().startsWith("****"));
        assertEquals("***", out.get("idCard").asText());
        assertTrue(out.get("phone").asText().startsWith("****"), "手机号应留尾脱敏");
        assertTrue(out.get("phone").asText().endsWith("8000"));
        assertTrue(out.get("email").asText().startsWith("****"), "邮箱应脱敏");
        assertTrue(out.get("ip").asText().startsWith("****"), "IP 应脱敏");
        assertTrue(out.get("ipAddress").asText().startsWith("****"), "日志 ipAddress 应脱敏");
    }

    @Test
    void passthroughFieldsStayIntact() throws Exception {
        String in = "{\"amount\":\"12345.67\",\"nickname\":\"张三\",\"realName\":\"李四\"}";
        JsonNode out = objectMapper.readTree(masker.mask(in));
        assertEquals("12345.67", out.get("amount").asText());
        assertEquals("张三", out.get("nickname").asText(), "人名不应脱敏（靠权限闸控制可见性）");
        assertEquals("李四", out.get("realName").asText());
    }

    @Test
    void masksNestedFieldsRecursively() throws Exception {
        String in = "{\"data\":{\"rows\":[{\"phone\":\"13900139000\"},{\"phone\":\"13700137000\"}]}}";
        JsonNode out = objectMapper.readTree(masker.mask(in));
        assertTrue(out.get("data").get("rows").get(0).get("phone").asText().startsWith("****"));
        assertTrue(out.get("data").get("rows").get(1).get("phone").asText().startsWith("****"));
    }

    @Test
    void nonJsonTextPassesThrough() {
        assertEquals("普通文本不脱敏", masker.mask("普通文本不脱敏"));
        assertEquals("", masker.mask(""));
    }

    @Test
    void shortValuesBecomeFixedMask() throws Exception {
        JsonNode out = objectMapper.readTree(masker.mask("{\"phone\":\"123\"}"));
        assertEquals("****", out.get("phone").asText(), "短于保留位数时整体打码");
    }
}
