package com.firestar.ai.core;

import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 统一 HTTP 客户端工厂（供应商无关）。
 *
 * <p><b>出网方式交由运行环境决定，应用代码不内置任何代理逻辑。</b>
 * 本机网络 / 系统代理 / 直连如何出公网，是部署环境问题，不应在业务代码里硬编码。
 * 若运行环境需要走代理出网，请在 JVM 层配置（如启动参数
 * {@code -Dhttps.proxyHost=... -Dhttps.proxyPort=...}，或操作系统 / 容器级代理），
 * 由 JDK 默认 {@link java.net.ProxySelector} 自动生效，本类不再干预。</p>
 */
@Component
public class HttpClientFactory {

    public HttpClient newClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public HttpClient newClient() {
        return newClient(Duration.ofSeconds(8));
    }
}
