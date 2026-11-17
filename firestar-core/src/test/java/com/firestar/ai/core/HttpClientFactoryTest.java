package com.firestar.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 共享 HttpClient 工厂语义测试：热切重建 provider 时复用同一实例，从根源消除连接池累积。
 */
class HttpClientFactoryTest {

    @Test
    @DisplayName("1. 相同超时返回同一实例（热切复用，不新建连接）")
    void sameTimeoutReturnsSameInstance() {
        HttpClientFactory factory = new HttpClientFactory();
        HttpClient a = factory.newClient(Duration.ofSeconds(10));
        HttpClient b = factory.newClient(Duration.ofSeconds(10));
        assertSame(a, b, "相同 connectTimeout 必须复用同一 HttpClient 实例");
    }

    @Test
    @DisplayName("2. 不同超时返回不同实例（不破坏 connectTimeout 语义）")
    void differentTimeoutReturnsDifferentInstance() {
        HttpClientFactory factory = new HttpClientFactory();
        HttpClient a = factory.newClient(Duration.ofSeconds(8));
        HttpClient b = factory.newClient(Duration.ofSeconds(10));
        assertNotSame(a, b, "不同 connectTimeout 应对应不同实例");
    }

    @Test
    @DisplayName("3. 无参 newClient() 与 8 秒超时的实例一致")
    void noArgMatchesEightSeconds() {
        HttpClientFactory factory = new HttpClientFactory();
        assertSame(factory.newClient(), factory.newClient(Duration.ofSeconds(8)));
    }

    @Test
    @DisplayName("4. 并发调用同一超时只产生一个实例（computeIfAbsent 线程安全）")
    void concurrentCallsProduceSingleInstance() throws Exception {
        HttpClientFactory factory = new HttpClientFactory();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<HttpClient> clients = java.util.Collections.synchronizedSet(new HashSet<>());
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        clients.add(factory.newClient(Duration.ofSeconds(10)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "并发任务应在 5 秒内完成");
            assertEquals(1, clients.size(), "并发下同一超时只能创建 1 个实例");
        } finally {
            pool.shutdownNow();
        }
    }
}
