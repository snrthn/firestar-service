package com.firestar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置绑定。application.properties 中前缀 jwt.*：
 *   jwt.secret     签名密钥（≥32 字节，上线替换为强随机值）
 *   jwt.expiration 有效期（毫秒，默认 7200000 = 2 小时）
 *   jwt.issuer    签发方标识
 */
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secret;
    private long expiration = 7200000L;
    private String issuer = "firestar";

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
