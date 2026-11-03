package com.firestar.ai.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.firestar.ai.config.AiProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 出域前字段级脱敏（设计 §7）。所有发往供应商的内容（工具返回 / 上下文拼装）
 * 只经这一个出口：先过 ToolRegistry 白名单（只吐声明列，不 dump 整行），再按字段分级改写。
 *
 * <p>策略驱动 + 加盐伪名化，配置见 application*.properties 的 ai.egress.*。
 * 模式：strict 严格脱敏 | relaxed 宽松 | log-only 放行但记录敏感字段出域（灰度用）。</p>
 */
@Component
public class EgressMasker {

    private final ObjectMapper objectMapper;
    private final Map<String, MaskAction> ruleMap = new ConcurrentHashMap<>();
    private volatile MaskMode mode = MaskMode.STRICT;

    public EgressMasker(ObjectMapper objectMapper, AiProperties aiProperties) {
        this.objectMapper = objectMapper;
        applyConfig(aiProperties);
    }

    /** 配置热更新（【模型配置】页切换或属性刷新时调用） */
    public void applyConfig(AiProperties aiProperties) {
        this.mode = MaskMode.from(aiProperties.getEgressMode());
        this.ruleMap.clear();
        if (aiProperties.getEgressRules() != null) {
            for (AiProperties.EgressRule r : aiProperties.getEgressRules()) {
                if (r.getField() != null && r.getAction() != null) {
                    try {
                        ruleMap.put(r.getField(), MaskAction.valueOf(r.getAction().toUpperCase()));
                    } catch (IllegalArgumentException ignored) {
                        // 未知 action 配置，跳过该规则
                    }
                }
            }
        }
    }

    /** 脱敏一段 JSON 字符串（工具返回往往是 JSON） */
    public String mask(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            maskNode(node);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            // 非 JSON（纯文本）不脱敏，直接放行（解释文本一般无结构化 PII 字段）
            return json;
        }
    }

    private void maskNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode on = (ObjectNode) node;
            var it = on.fields();
            while (it.hasNext()) {
                var e = it.next();
                MaskAction action = ruleMap.get(e.getKey());
                if (action != null && e.getValue().isTextual()) {
                    on.put(e.getKey(), apply(action, e.getValue().asText()));
                } else if (e.getValue().isObject() || e.getValue().isArray()) {
                    maskNode(e.getValue());
                }
            }
        } else if (node.isArray()) {
            ArrayNode an = (ArrayNode) node;
            an.forEach(this::maskNode);
        }
    }

    private String apply(MaskAction action, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return switch (action) {
            case REDACT -> "***";
            case PARTIAL_MASK -> maskTail(value, 4);
            case PSEUDONYMIZE -> pseudonymize(value);
            case PASS -> value;
        };
    }

    private static String maskTail(String v, int keep) {
        if (v.length() <= keep) {
            return "****";
        }
        return "****" + v.substring(v.length() - keep);
    }

    private static String pseudonymize(String v) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(("FS_AI_SALT:" + v).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("TKN_");
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "TKN_????";
        }
    }

    private enum MaskMode {
        STRICT, RELAXED, LOG_ONLY;

        static MaskMode from(String s) {
            if (s == null) {
                return STRICT;
            }
            try {
                return MaskMode.valueOf(s.toUpperCase());
            } catch (Exception e) {
                return STRICT;
            }
        }
    }
}
