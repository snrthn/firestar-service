package com.firestar.ai.core;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扣子插件回调 HMAC-SHA256 校验（设计 §4.3 / 安全红线）。
 *
 * <p>扣子对每次工具调用用平台「自定义插件」配置的公钥(secret) 签名，放在请求头。
 * 本实现按通用约定拼接 {@code timestamp + "\n" + nonce + "\n" + body} 后做 HMAC-SHA256，
 * 并校验时间戳 5 分钟内（防重放）。</p>
 *
 * <p>⚠️ 与扣子平台签名规范对齐：若平台实际头名 / 拼接顺序不同，
 * 仅改下方 {@code SIGNATURE_HEADER / TIMESTAMP_HEADER / NONCE_HEADER} 常量与拼接串即可，
 * 不动验签主流程。</p>
 */
@Component
public class CozePluginVerifier {

    private static final String SIGNATURE_HEADER = "X-Coze-Signature";
    private static final String TIMESTAMP_HEADER = "X-Coze-Timestamp";
    private static final String NONCE_HEADER = "X-Coze-Nonce";
    private static final long MAX_AGE_SECONDS = 300;

    /** 已使用 nonce → 过期时间戳（秒）。5 分钟窗口内同一 nonce 仅可成功验签一次，防重放。 */
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();
    private static final int MAX_SEEN = 2000;

    private final String secret;

    public CozePluginVerifier(@Value("${ai.coze.plugin-secret:}") String secret) {
        this.secret = secret;
    }

    @PostConstruct
    void warnIfMissing() {
        if (secret == null || secret.isBlank()) {
            // 不抛异常（避免未配置插件时就启动失败）；但所有插件回调会被拒，需在配置页补齐。
            org.slf4j.LoggerFactory.getLogger(CozePluginVerifier.class)
                    .warn("ai.coze.plugin-secret 未配置：扣子插件回调将全部被拒，请在【模型配置】或环境变量 COZE_PLUGIN_SECRET 设置");
        }
    }

    public boolean verify(String signature, String timestamp, String nonce, String body) {
        if (secret == null || secret.isBlank() || signature == null || timestamp == null || nonce == null) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - ts) > MAX_AGE_SECONDS) {
                return false; // 重放防护（时间窗）
            }
        } catch (NumberFormatException e) {
            return false;
        }
        // 重放防护（nonce 去重）：5 分钟窗口内同一 nonce 仅可成功验签一次。
        long now = Instant.now().getEpochSecond();
        Long existing = seenNonces.get(nonce);
        if (existing != null) {
            if (existing > now) {
                return false; // 未过期 → 原样重放，拒绝
            }
            seenNonces.remove(nonce); // 已过期，允许复用
        }
        String payload = timestamp + "\n" + nonce + "\n" + (body == null ? "" : body);
        String expected = hmacSha256(payload);
        boolean ok = expected != null && constantTimeEquals(expected, signature);
        if (ok) {
            // 验签成功才登记 nonce；失败不登记（避免攻击者用错签名预占 nonce）。
            seenNonces.put(nonce, now + MAX_AGE_SECONDS);
            if (seenNonces.size() > MAX_SEEN) {
                seenNonces.entrySet().removeIf(e -> e.getValue() <= now);
            }
        }
        return ok;
    }

    private String hmacSha256(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
