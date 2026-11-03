package com.firestar.ai.crypto;

import com.firestar.ai.config.AiProperties;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;
import java.util.Base64;

/**
 * 国密 SM4-GCM 字段加密（满足密评 / 等保三级"数据存储安全"条款，设计 §5.4）。
 *
 * <p>密文格式：{@code enc:v1: + Base64( IV(12字节) ‖ SM4-GCM密文(含16字节tag) )}。
 * KEK 由环境变量 AI_KEK 注入（缺失即启动失败，见 AiProperties）；DB 只存密文，KEK 不进库。</p>
 *
 * <p>算法选 SM4 而非 AES 是为合规；SM4-GCM 与 AES-GCM 调用方式一致，后续若政策允许改 AES 仅换算法名。</p>
 */
@Service
public class CryptoService {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static final String PREFIX = "enc:v1:";
    private static final String TRANSFORM = "SM4/GCM/NoPadding";
    private static final String PROVIDER = "BC";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private final byte[] key; // 派生出的稳定 16 字节 SM4 密钥

    public CryptoService(AiProperties aiProperties) {
        // KEK 长度不固定，派生为稳定 16 字节 SM4 密钥（SHA-256 截断），避免长度约束。
        // KEK 由环境变量 AI_KEK 注入，缺失即启动失败（见 AiProperties.validate）。
        String kek = aiProperties.getKek();
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(kek.getBytes(StandardCharsets.UTF_8));
            this.key = Arrays.copyOf(h, 16);
        } catch (Exception e) {
            throw new IllegalStateException("AI KEK 派生失败", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORM, PROVIDER);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "SM4"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AI 字段加密失败", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored; // 未加密（兼容明文 / 历史值）
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(raw, 0, IV_LEN);
            byte[] ct = Arrays.copyOfRange(raw, IV_LEN, raw.length);
            Cipher c = Cipher.getInstance(TRANSFORM, PROVIDER);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "SM4"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AI 字段解密失败", e);
        }
    }

    public boolean isEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }
}
