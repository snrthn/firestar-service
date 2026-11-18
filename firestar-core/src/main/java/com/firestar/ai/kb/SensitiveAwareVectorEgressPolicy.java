package com.firestar.ai.kb;

import com.firestar.ai.config.KbVectorEgressProperties;
import com.firestar.ai.entity.KbDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 敏感感知向量出域策略（默认）：禁出域文档 / 敏感角色文档不送第三方 embedding。
 *
 * <p>双判定（任一命中即拦截）：
 * <ol>
 *   <li>{@code egress_allowed = 0}：内容级出域开关显式关闭；</li>
 *   <li>{@code acl_roles} 命中敏感角色名单（HR 管理者 / 主管 / 财务经理等，可配）。</li>
 * </ol>
 * {@code egress_allowed=null} 按 1 放行（与 DDL 默认 + KbDocService VO 口径一致），
 * 不因历史数据缺值误拦普通文档。</p>
 *
 * <p><b>安全默认</b>：{@code matchIfMissing=true}——未配置 kb.vectorize.egress.policy 时也生效，
 * 避免「忘了配 = 全量出域」的危险默认。要全放行必须显式配 {@code allow-all}。</p>
 */
@Component
@ConditionalOnProperty(name = "kb.vectorize.egress.policy", havingValue = "sensitive-aware", matchIfMissing = true)
public class SensitiveAwareVectorEgressPolicy implements KbVectorEgressPolicy {

    private static final Logger log = LoggerFactory.getLogger(SensitiveAwareVectorEgressPolicy.class);

    private final KbVectorEgressProperties props;

    @Autowired
    public SensitiveAwareVectorEgressPolicy(KbVectorEgressProperties props) {
        this.props = props;
    }

    @Override
    public boolean canEmbed(KbDoc doc) {
        if (doc == null) {
            return false;
        }
        Integer egressAllowed = doc.getEgressAllowed();
        if (egressAllowed != null && egressAllowed == 0) {
            log.info("[KB-VECTOR-EGRESS] 拦截 docId={}：egress_allowed=0", doc.getId());
            return false;
        }
        if (props.isSensitive(doc.getAclRoles())) {
            log.info("[KB-VECTOR-EGRESS] 拦截 docId={}：acl_roles 命中敏感名单 ({})",
                    doc.getId(), doc.getAclRoles());
            return false;
        }
        return true;
    }

    @Override
    public String id() {
        return KbVectorEgressProperties.POLICY_SENSITIVE_AWARE;
    }
}
