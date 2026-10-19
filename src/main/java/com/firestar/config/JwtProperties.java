package com.firestar.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * JWT 配置绑定。application.properties 中前缀 jwt.*：
 *   jwt.secret     签名密钥（≥32 字节，**由环境变量 JWT_SECRET 注入**，见下方校验）
 *   jwt.expiration 有效期（毫秒，默认 7200000 = 2 小时）
 *   jwt.issuer     签发方标识
 */
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** HS256 要求的最小密钥长度（字节）。jjwt 的 Keys.hmacShaKeyFor 也按这个门槛抛 WeakKeyException */
    private static final int MIN_SECRET_BYTES = 32;

    private String secret;
    private long expiration = 7200000L;
    private String issuer = "firestar";

    /**
     * 启动即校验密钥，宁可起不来也不带病上线。
     *
     * <p>【为什么要在绑定之后再做一遍，而不是只靠占位符】
     * 直觉上 {@code jwt.secret=${JWT_SECRET}} 在环境变量缺失时会报
     * "Could not resolve placeholder 'JWT_SECRET'"，但 {@code @ConfigurationProperties}
     * 的宽松绑定并不保证这样：实测（2026-10-19，prod profile 不设 JWT_SECRET）它把
     * **字面量** {@code ${JWT_SECRET}} 直接绑进来了，应用继续往下走。需要在这里拦的情形：
     * <ul>
     *   <li>占位符未解析，值就是字面量 {@code ${JWT_SECRET}}（13 字节）——
     *       会一路撑到签名阶段才炸，或者更糟：真的用这 13 个字符当密钥；</li>
     *   <li>环境变量存在但为空串（{@code JWT_SECRET=}）；</li>
     *   <li>配了个太短的值（如 8 位）—— 启动后期才抛 WeakKeyException，容易被当成代码 bug。</li>
     * </ul>
     * 在这里一次性拦下，并把「该去哪儿配」写进报错里。
     */
    @PostConstruct
    void validate() {
        // ⚠️ 必须显式识别「占位符没被解析」的情形：实测（2026-10-19，prod profile 不设 JWT_SECRET）
        // @ConfigurationProperties 的宽松绑定不会为未解析的占位符抛异常，而是把 **字面量**
        // "${JWT_SECRET}" 原样塞进来 —— 长度恰好 13 字节。若只校验长度，报错会变成
        // 「长度不足：当前 13 字节」，把「根本没配」误导成「配得太短」，排查方向就跑偏了。
        boolean unresolved = secret != null && secret.contains("${");
        if (secret == null || secret.isBlank() || unresolved) {
            throw new IllegalStateException(
                    "jwt.secret 未配置：请通过环境变量 JWT_SECRET 注入 ≥32 字节的强随机密钥"
                            + "（生成方式：openssl rand -base64 48）。"
                            + "生产/预发配在云效「主机部署」私密变量；本地开发配在 .env/dev.env。");
        }
        int len = secret.getBytes(StandardCharsets.UTF_8).length;
        if (len < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 长度不足：当前 " + len + " 字节，HS256 要求至少 " + MIN_SECRET_BYTES + " 字节。"
                            + "请用 openssl rand -base64 48 重新生成后写入环境变量 JWT_SECRET。");
        }
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpiration() {
        return expiration;
    }

    public void setExpiration(long expiration) {
        this.expiration = expiration;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
