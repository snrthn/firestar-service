package com.firestar.ai.kb;

import com.firestar.ai.config.KbVectorEgressProperties;
import com.firestar.ai.entity.KbDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SensitiveAwareVectorEgressPolicy：向量化出域双判定")
class SensitiveAwareVectorEgressPolicyTest {

    private SensitiveAwareVectorEgressPolicy policy;

    @BeforeEach
    void setUp() {
        KbVectorEgressProperties props = new KbVectorEgressProperties();
        props.setSensitiveRoles("ROLE_HR_MANAGER, ROLE_SUPERVISOR, ROLE_FINANCE_MANAGER");
        props.validate();
        policy = new SensitiveAwareVectorEgressPolicy(props);
    }

    private KbDoc doc(Integer egressAllowed, String aclRoles) {
        KbDoc d = new KbDoc();
        d.setId(1L);
        d.setEgressAllowed(egressAllowed);
        d.setAclRoles(aclRoles);
        return d;
    }

    @Test
    @DisplayName("egress_allowed=0 → 拦截（不看 acl）")
    void egressDisabledBlocked() {
        assertFalse(policy.canEmbed(doc(0, null)));
        assertFalse(policy.canEmbed(doc(0, "ROLE_SALES_MANAGER")));
    }

    @Test
    @DisplayName("acl_roles 命中敏感名单 → 拦截")
    void sensitiveRoleBlocked() {
        assertFalse(policy.canEmbed(doc(1, "ROLE_HR_MANAGER")));
        assertFalse(policy.canEmbed(doc(1, "ROLE_CASHIER,ROLE_FINANCE_MANAGER")));
    }

    @Test
    @DisplayName("普通文档（egress=1 + 非敏感角色）→ 放行")
    void normalDocAllowed() {
        assertTrue(policy.canEmbed(doc(1, "ROLE_SALES_MANAGER")));
        assertTrue(policy.canEmbed(doc(1, null)));
    }

    @Test
    @DisplayName("egress_allowed=null 按 1 放行（历史数据不误拦）")
    void nullEgressPass() {
        assertTrue(policy.canEmbed(doc(null, null)));
        assertTrue(policy.canEmbed(doc(null, "ROLE_CASHIER")));
    }

    @Test
    @DisplayName("doc=null → 拦截（防御）")
    void nullDocBlocked() {
        assertFalse(policy.canEmbed(null));
    }

    @Test
    @DisplayName("策略标识 = sensitive-aware")
    void policyId() {
        assertEquals("sensitive-aware", policy.id());
    }
}
