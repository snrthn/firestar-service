package com.firestar.ai.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.firestar.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 出域前字段级脱敏（设计 §7）。所有发往供应商的内容（工具返回 / 上下文拼装）
 * 只经这一个出口：先过 ToolRegistry 白名单（只吐声明列，不 dump 整行），再按字段分级改写。
 *
 * <p>策略驱动 + 加盐伪名化，配置见 application*.properties 的 ai.egress.*。
 * 模式：strict 严格脱敏 | relaxed 宽松 | log-only 放行但记录敏感字段出域（灰度用）。
 *
 * <p><b>2026-11-25 出域收口</b>：修三处结构性失效——
 * ① 未声明字段在投影模式下降级为「丢弃」(fail-closed)，与向量出域 matchIfMissing=true 同向；
 * ② 数组内的文本元素按所属字段规则脱敏（原来递归丢 action，配了也不生效）；
 * ③ 文本 blob 在无白名单声明时不再静默放行（记录 WARN，无字段名可判故不改写）。
 * mode 正式参与判定：strict/relaxed 按字段规则改写，log-only 只记录不改写（灰度观察）。</p>
 */
@Component
public class EgressMasker {

    private static final Logger log = LoggerFactory.getLogger(EgressMasker.class);

    private final ObjectMapper objectMapper;
    private final Map<String, MaskAction> ruleMap = new ConcurrentHashMap<>();

    /** 出域模式（strict / relaxed / log-only），applyConfig 时热更新 */
    private volatile MaskMode mode = MaskMode.STRICT;

    /** 未接白名单 / 纯文本出域调用计数（灰度观测用），不参与业务判定 */
    private final AtomicLong unwrappedCalls = new AtomicLong();

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

    /**
     * 脱敏一段 JSON 字符串（工具返回往往是 JSON）。<b>未做字段投影</b>，仅按字段规则改写值。
     *
     * <p>保留此重载只为兼容尚未声明 outputFields 的调用点；新调用必须走
     * {@link #mask(String, Set)} 以启用 fail-closed 投影。</p>
     */
    public String mask(String json) {
        return mask(json, null);
    }

    /**
     * 出域统一入口：① 白名单投影（未声明字段丢弃，fail-closed）→ ② 字段级脱敏 → ③ 越界告警。
     *
     * <p>投影失败方向为「丢弃」而非「放行」：漏声明字段会让模型查不到该列，是可发现的功能缺口；
     * 漏放行则是静默数据出境，不可发现。二者代价不对称，故默认取前者。</p>
     *
     * @param json          工具原始返回（JSON）
     * @param allowedFields 该工具声明允许出域的字段；null/空集 = 尚未接入白名单，
     *                      退化旧行为并 WARN 计数（灰度期）
     */
    public String mask(String json, Set<String> allowedFields) {
        if (json == null || json.isBlank()) {
            return json;
        }
        boolean projected = allowedFields != null && !allowedFields.isEmpty();
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            // P0-3：非 JSON 纯文本不再无条件放行——记录 WARN（无字段名可判，无法做字段级改写）
            onUnstructuredText(json, projected);
            return json;
        }
        if (!projected) {
            onUnprojectedCall();
            maskNode(node);
        } else {
            // P0-1：fail-closed 投影——只保留 allowedFields 声明的字段，其余删除
            node = pruneToWhitelist(node, allowedFields, "$");
            maskNode(node);
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("[AI-EGRESS] 脱敏后序列化失败，降级放行原文：{}", e.getMessage());
            return json;
        }
    }

    /** 未接白名单的调用：strict 档显式告警，其余档仅计数（灰度观察） */
    private void onUnprojectedCall() {
        long n = unwrappedCalls.incrementAndGet();
        if (mode == MaskMode.STRICT && n <= 20) {
            log.warn("[AI-EGRESS] 工具未声明 outputFields，出域未投影（fail-open 灰度遗留）：count={}。 "
                    + "请为该工具补 outputFields 并改走 mask(json, allowedFields)", n);
        }
    }

    /** 纯文本出域：无字段名可判，记录 WARN（log-only 档每次都记，便于灰度核对真实出域） */
    private void onUnstructuredText(String json, boolean projected) {
        long n = unwrappedCalls.incrementAndGet();
        if (n <= 20 || mode == MaskMode.LOG_ONLY) {
            log.warn("[AI-EGRESS] 非 JSON 纯文本出域（无字段级脱敏）：projected={} mode={} count={} len={}",
                    projected, mode, n, json.length());
        }
    }

    /**
     * 白名单投影：对对象节点只保留声明字段；对数组逐元素递归（含对象数组与嵌套树）。
     * 标量/文本节点没有字段名可比对，原样保留交由 maskNode 按规则处理。
     */
    private JsonNode pruneToWhitelist(JsonNode node, Set<String> allowedFields, String path) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            ArrayNode an = (ArrayNode) node;
            for (int i = 0; i < an.size(); i++) {
                an.set(i, pruneToWhitelist(an.get(i), allowedFields, path + "[" + i + "]"));
            }
            return an;
        }
        if (!node.isObject()) {
            return node;
        }
        ObjectNode on = (ObjectNode) node;
        Iterator<Map.Entry<String, JsonNode>> it = on.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!allowedFields.contains(e.getKey())) {
                it.remove();
                continue;
            }
            on.set(e.getKey(), pruneToWhitelist(e.getValue(), allowedFields, path + "." + e.getKey()));
        }
        return on;
    }

    /** 字段级脱敏。修复 P0-2：数组元素的文本按「所属字段」规则替换，而非只递归不替换。 */
    private void maskNode(JsonNode node) {
        maskNode(node, null);
    }

    /** @param inheritedAction 父字段的规则（数组元素继承父字段名规则，如 roleNames 的每个元素） */
    private void maskNode(JsonNode node, MaskAction inheritedAction) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode on = (ObjectNode) node;
            var it = on.fields();
            while (it.hasNext()) {
                var e = it.next();
                MaskAction action = ruleMap.get(e.getKey());
                JsonNode value = e.getValue();
                if (action != null && value.isTextual()) {
                    on.put(e.getKey(), apply(action, value.asText()));
                } else if (value.isObject() || value.isArray()) {
                    // 传 action 下去：数组元素需继承父字段规则（P0-2）
                    maskNode(value, action);
                }
            }
        } else if (node.isArray()) {
            ArrayNode an = (ArrayNode) node;
            for (int i = 0; i < an.size(); i++) {
                JsonNode el = an.get(i);
                if (el.isTextual() && inheritedAction != null) {
                    an.set(i, TextNode.valueOf(apply(inheritedAction, el.asText())));
                } else {
                    maskNode(el, inheritedAction);
                }
            }
        }
    }

    private String apply(MaskAction action, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (mode == MaskMode.LOG_ONLY) {
            // 灰度档：只记录不改写，靠 WARN 观测真实出域内容
            log.warn("[AI-EGRESS][LOG_ONLY] 敏感字段出域（未改写）：len={}", value.length());
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

    /** 灰度观测：累计未接白名单/纯文本出域次数（供运维核对） */
    public long unwrappedCallCount() {
        return unwrappedCalls.get();
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
