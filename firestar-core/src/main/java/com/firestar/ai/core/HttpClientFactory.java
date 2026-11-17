package com.firestar.ai.core;

import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 按 connectTimeout 复用共享的 HttpClient 实例。
     *
     * <p>JDK 17 的 HttpClient 内部持有连接池与selector管理线程，本身线程安全、适合共享。
     * 本工厂是 Spring 单例，ChatProviderManager.reload() 热切时会重建各 provider；
     * 若每次构建都 new 一个 HttpClient，旧实例的连接池无法显式 close（JDK17 HttpClient 无 close()），
     * 会随热切不断累积。改为按超时缓存共享实例后，provider 重建不再新建连接，泄漏从根源消除。
     * 每个 connectTimeout 值对应一个实例，连接按目标 host 由 JDK 内部自动隔离复用。</p>
     */
    private final ConcurrentHashMap<Duration, HttpClient> sharedClients = new ConcurrentHashMap<>();

    public HttpClient newClient(Duration connectTimeout) {
        return sharedClients.computeIfAbsent(connectTimeout, timeout -> HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public HttpClient newClient() {
        return newClient(Duration.ofSeconds(8));
    }
}
