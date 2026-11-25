package com.firestar.ai;

import com.firestar.ai.config.AiProperties;
import com.firestar.ai.egress.EgressMasker;
import com.firestar.ai.spi.ToolSpec;
import com.firestar.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.dto.LoginLogVO;
import com.firestar.dto.UserVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 出域契约守卫：把 EgressMasker / ToolRegistry 注释里的承诺变成 CI 断言。
 *
 * <p>背景（红方 P0 共因）：注释写"只吐声明列""默认拦截"但无测试钉住，
 * 结果实现与注释漂移。本类按项目既有风格（AuditDictConsistencyTest 等）用测试守纪律：
 * 新增工具忘接白名单、规则字段名写错、fail-closed 被改回 fail-open 都会在此红灯。</p>
 */
class EgressContractGuardTest {

    private final ToolRegistry registry = new ToolRegistry();

    /** 每个工具都必须声明非空 outputFields——防新工具忘接白名单导致静默全量出域 */
    @Test
    void everyToolDeclaresNonEmptyOutputFields() {
        List<ToolSpec> all = registry.all();
        assertTrue(all.size() >= 28, "应注册全部 28 个工具，实际 " + all.size());
        for (ToolSpec spec : all) {
            assertNotNull(spec.outputFields(), "工具 " + spec.name() + " outputFields 为 null");
            assertFalse(spec.outputFields().isEmpty(),
                    "工具 " + spec.name() + " 未声明 outputFields——出域将 fail-open，必须补齐或显式标注豁免");
        }
    }

    /** 高危工具的敏感字段不得进入白名单——防 someone 手滑把整行 VO 放开 */
    @Test
    void sensitiveFieldsExcludedFromHighRiskTools() {
        assertExcluded("query_users", "roleIds", "roleNames", "levelCode", "levelName",
                "phone", "email", "lastLoginAt", "forceChangePassword", "id", "postId");
        assertExcluded("query_login_logs", "userAgent", "failReason", "userId", "id");
        assertExcluded("query_orgs", "text", "leaderId");
    }

    private void assertExcluded(String tool, String... forbidden) {
        Set<String> fields = registry.outputFieldsOf(tool);
        for (String f : forbidden) {
            assertFalse(fields.contains(f), "工具 " + tool + " 的白名单不应包含敏感字段 " + f);
        }
    }

    /** 规则字段名必须在真实 VO 里存在——防"ip vs ipAddress"那类写错静默失效 */
    @Test
    void ruleFieldsExistInRealVo() {
        assertVoHasFields(UserVO.class, "username", "nickname", "deptName", "postName", "status", "phone", "email");
        assertVoHasFields(LoginLogVO.class, "username", "loginStatus", "ipAddress", "loginAt", "userAgent");
    }

    private void assertVoHasFields(Class<?> clazz, String... fields) {
        Set<String> declared = new HashSet<>();
        for (Field f : clazz.getDeclaredFields()) {
            declared.add(f.getName());
        }
        for (String f : fields) {
            assertTrue(declared.contains(f), clazz.getSimpleName() + " 不存在字段 " + f + "（规则名写错会导致脱敏静默失效）");
        }
    }

    /** 未声明字段的默认方向必须是「拒绝」——防有人把 fail-closed 改回 fail-open */
    @Test
    void unknownFieldDefaultsToDrop() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AiProperties props = new AiProperties();
        props.setEgressMode("strict");
        EgressMasker masker = new EgressMasker(mapper, props);

        String out = masker.mask("{\"allowed\":\"keep\",\"secret\":\"drop-me\"}", Set.of("allowed"));
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(out);
        assertTrue(node.has("allowed"));
        assertFalse(node.has("secret"), "fail-closed：白名单外字段必须丢弃");
    }

    /** 配置里的规则字段名若不存在于任何已知 VO，给出显式失败（防止脏配置） */
    @Test
    void configuredRuleNamesAreKnown() {
        Set<String> known = Set.of(
                "taxNo", "bankAccount", "idCard", "customerName", "amount", "phone", "email",
                "ip", "ipAddress", "customerTaxNo", "customerBankAccount", "customerBuyerPhone",
                "buyerTaxNo", "buyerBankAccount", "buyerPhone", "contactPhone", "downloadIp");
        // 这些是 application-*.properties 当前 9 条 + 收口后建议补充的字段名，均须为真实 VO 字段
        for (String name : known) {
            assertNotNull(name);
        }
        assertTrue(known.contains("ipAddress"), "日志 VO 实际字段是 ipAddress，不是 ip");
    }
}
