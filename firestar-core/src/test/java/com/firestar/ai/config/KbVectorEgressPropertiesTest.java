package com.firestar.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KbVectorEgressProperties：向量出域配置 + 敏感角色判定")
class KbVectorEgressPropertiesTest {

    private KbVectorEgressProperties props(String roles) {
        KbVectorEgressProperties p = new KbVectorEgressProperties();
        p.setSensitiveRoles(roles);
        p.validate();
        return p;
    }

    @Test
    @DisplayName("默认策略为 sensitive-aware，安全默认不误拦")
    void defaultPolicyIsSensitiveAware() {
        KbVectorEgressProperties p = new KbVectorEgressProperties();
        p.validate();
        assertEquals("sensitive-aware", p.getPolicy());
        assertTrue(p.isSensitiveAware());
        assertTrue(p.getSensitiveRoleSet().contains("ROLE_HR_MANAGER"));
    }

    @Test
    @DisplayName("acl_roles 命中敏感名单（含逗号多角色/空格）")
    void sensitiveRoleHit() {
        KbVectorEgressProperties p = props("ROLE_HR_MANAGER, ROLE_FINANCE_MANAGER");
        assertTrue(p.isSensitive("ROLE_HR_MANAGER"));
        assertTrue(p.isSensitive("ROLE_CASHIER,ROLE_FINANCE_MANAGER"));
        assertTrue(p.isSensitive("  ROLE_HR_MANAGER  "));
    }

    @Test
    @DisplayName("acl_roles 未命中/为空 → 放行")
    void nonSensitivePass() {
        KbVectorEgressProperties p = props("ROLE_HR_MANAGER,ROLE_SUPERVISOR");
        assertFalse(p.isSensitive("ROLE_SALES_MANAGER"));
        assertFalse(p.isSensitive(null));
        assertFalse(p.isSensitive(""));
        assertFalse(p.isSensitive("   "));
    }

    @Test
    @DisplayName("空名单 → 无角色敏感（egress_allowed 仍独立生效）")
    void emptyRoleList() {
        KbVectorEgressProperties p = props("");
        assertTrue(p.getSensitiveRoleSet().isEmpty());
        assertFalse(p.isSensitive("ROLE_HR_MANAGER"));
    }

    @Test
    @DisplayName("未知 policy 值 → fail-fast 并列出可选值")
    void invalidPolicyFails() {
        KbVectorEgressProperties p = new KbVectorEgressProperties();
        p.setPolicy("bogus");
        IllegalStateException ex = assertThrows(IllegalStateException.class, p::validate);
        assertTrue(ex.getMessage().contains("sensitive-aware"));
        assertTrue(ex.getMessage().contains("allow-all"));
    }

    @Test
    @DisplayName("allow-all 显式配置生效")
    void allowAllConfigured() {
        KbVectorEgressProperties p = new KbVectorEgressProperties();
        p.setPolicy("allow-all");
        p.validate();
        assertFalse(p.isSensitiveAware());
    }
}
