package com.firestar.ai.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 向量化出域准入配置（前缀 kb.vectorize.egress.*，见 application.properties）。
 *
 * <p>P1#5 出域闸：向量化（尤其 cloud embedding）会把切片正文发送到第三方，必须先把关。
 * 本配置<b>独立于</b> {@code kb.egress.policy}（那是「检索结果回模型」侧的准入），
 * 二者互不干扰——切向量出域策略不影响检索链路与 KbPropertiesTest。</p>
 *
 * <p>policy 两值：
 * <ul>
 *   <li>{@code sensitive-aware}（默认）：{@code egress_allowed=0} 或 {@code acl_roles} 命中敏感名单的文档
 *       禁止向量化（正文不出域）；</li>
 *   <li>{@code allow-all}：全部向量化（等价于出域闸关闭，仅用于确认无敏感内容的库）。</li>
 * </ul>
 * 未知值 fail-fast，避免「以为拦了其实没拦」。</p>
 */
@Component
@ConfigurationProperties(prefix = "kb.vectorize.egress")
public class KbVectorEgressProperties {

    private static final Logger log = LoggerFactory.getLogger(KbVectorEgressProperties.class);

    public static final String POLICY_SENSITIVE_AWARE = "sensitive-aware";
    public static final String POLICY_ALLOW_ALL = "allow-all";

    /** 默认敏感策略：受限/禁出域文档不送第三方。 */
    private String policy = POLICY_SENSITIVE_AWARE;

    /**
     * 敏感角色名单：acl_roles 命中其中任一角色即禁止向量化。
     * 默认覆盖常见受限岗（HR 管理者 / 主管），可在配置里覆盖。
     */
    private String sensitiveRoles = "ROLE_HR_MANAGER,ROLE_SUPERVISOR,ROLE_FINANCE_MANAGER";

    private Set<String> sensitiveRoleSet = new LinkedHashSet<>();

    @PostConstruct
    public void validate() {
        if (!POLICY_SENSITIVE_AWARE.equals(policy) && !POLICY_ALLOW_ALL.equals(policy)) {
            throw new IllegalStateException("[KB-VECTOR-EGRESS] kb.vectorize.egress.policy='" + policy
                    + "' 不合法，可选值：[" + POLICY_SENSITIVE_AWARE + ", " + POLICY_ALLOW_ALL + "]");
        }
        sensitiveRoleSet.clear();
        if (sensitiveRoles != null && !sensitiveRoles.isBlank()) {
            Arrays.stream(sensitiveRoles.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(sensitiveRoleSet::add);
        }
        log.info("[KB-VECTOR-EGRESS] 配置 OK: policy={}, sensitiveRoles={}", policy, sensitiveRoleSet);
    }

    /** 文档 acl_roles 是否命中敏感名单（逗号分隔多角色，命中任一即 true）。 */
    public boolean isSensitive(String aclRoles) {
        if (aclRoles == null || aclRoles.isBlank() || sensitiveRoleSet.isEmpty()) {
            return false;
        }
        for (String role : aclRoles.split(",")) {
            if (sensitiveRoleSet.contains(role.trim())) {
                return true;
            }
        }
        return false;
    }

    public boolean isSensitiveAware() {
        return POLICY_SENSITIVE_AWARE.equals(policy);
    }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
    public String getSensitiveRoles() { return sensitiveRoles; }
    public void setSensitiveRoles(String sensitiveRoles) { this.sensitiveRoles = sensitiveRoles; }
    public Set<String> getSensitiveRoleSet() { return sensitiveRoleSet; }
}
