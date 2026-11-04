package com.firestar.ai.core;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coze 插件回调验签回归测试（覆盖 /ai/coze/plugin 匿名入口的安全核心）。
 * 重点守护：签名正确、错误签名拒绝、过期时间戳拒绝、nonce 窗口内重放拒绝。
 */
class CozePluginVerifierTest {

    private static final String SECRET = "test-secret";
    private final CozePluginVerifier verifier = new CozePluginVerifier(SECRET);

    private String sign(String timestamp, String nonce, String body) throws Exception {
        String payload = timestamp + "\n" + nonce + "\n" + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private String nowTs() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    @Test
    void validSignature_passes() throws Exception {
        String ts = nowTs(), nonce = "n1", body = "{}";
        assertTrue(verifier.verify(sign(ts, nonce, body), ts, nonce, body));
    }

    @Test
    void wrongSignature_fails() throws Exception {
        String ts = nowTs(), nonce = "n2", body = "{}";
        assertFalse(verifier.verify("deadbeef", ts, nonce, body));
    }

    @Test
    void expiredTimestamp_fails() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000 - 600); // 10 分钟前
        String nonce = "n3", body = "{}";
        assertFalse(verifier.verify(sign(ts, nonce, body), ts, nonce, body));
    }

    @Test
    void replayedNonce_withinWindow_fails() throws Exception {
        String ts = nowTs(), nonce = "n4", body = "{}";
        String sig = sign(ts, nonce, body);
        assertTrue(verifier.verify(sig, ts, nonce, body));   // 首次成功
        assertFalse(verifier.verify(sig, ts, nonce, body));   // 同 nonce 重放拒绝
    }

    @Test
    void differentNonce_bothSucceed() throws Exception {
        String ts = nowTs();
        assertTrue(verifier.verify(sign(ts, "a", "{}"), ts, "a", "{}"));
        assertTrue(verifier.verify(sign(ts, "b", "{}"), ts, "b", "{}"));
    }

    @Test
    void missingSecret_rejects() {
        CozePluginVerifier empty = new CozePluginVerifier("");
        assertFalse(empty.verify("x", "1", "n", "b"));
    }
}
