package com.firestar.realtime;

import jakarta.annotation.PreDestroy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 基于 SseEmitter 的实时推送网关（单实例实现）。
 *
 * 连接管理：ConcurrentHashMap&lt;username, CopyOnWriteArrayList&lt;SseEmitter&gt;&gt;。
 * 一个用户可能开多个标签页 → 多个 emitter，推送时全部送达。
 *
 * 心跳保活：nginx 默认 proxy_read_timeout 60s 会断开空闲长连接，故定时发 SSE 注释帧
 *（以 ':' 开头，浏览器不触发 onmessage）维持连接活跃。
 *
 * 预留多实例：若部署多个后端实例，把本类换成 RedisRealtimeGateway
 *（Redis pub/sub 做跨实例广播，本地只推自己持有的连接），接口不变，
 * 在 RedisRealtimeGateway 上加 @Primary 即可，业务与事件发布代码无需任何改动。
 */
@Service
public class SseRealtimeGateway implements RealtimeGateway {

    private static final long SSE_TIMEOUT_MS = 3_600_000L; // 1h，与 nginx proxy_read_timeout 对齐
    private static final long HEARTBEAT_MS = 25_000L;       // 小于 nginx 超时，留足余量

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public SseRealtimeGateway() {
        heartbeatScheduler.scheduleAtFixedRate(this::heartbeatAll,
                HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public SseEmitter connect(String username, String jti) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onCompletion(() -> remove(username, emitter));
        emitter.onTimeout(() -> remove(username, emitter));
        emitter.onError(e -> remove(username, emitter));
        connections.computeIfAbsent(username, k -> new CopyOnWriteArrayList<>()).add(emitter);
        return emitter;
    }

    @Override
    public void pushToUser(String username, RealtimeEventType type, Object payload) {
        List<SseEmitter> list = connections.get(username);
        if (list == null) return;
        for (SseEmitter em : list) {
            try {
                em.send(SseEmitter.event().name(type.name()).data(payload == null ? "{}" : payload));
            } catch (IOException | IllegalStateException e) {
                remove(username, em);
            }
        }
    }

    @Override
    public void broadcast(RealtimeEventType type, Object payload) {
        connections.keySet().forEach(u -> pushToUser(u, type, payload));
    }

    /** 订阅业务侧发布的 RealtimeEvent，路由到具体用户或全部 */
    @EventListener
    public void onRealtimeEvent(RealtimeEvent event) {
        if (event.getUsername() != null) {
            pushToUser(event.getUsername(), event.getType(), event.getPayload());
        } else {
            broadcast(event.getType(), event.getPayload());
        }
    }

    private void heartbeatAll() {
        connections.forEach((username, list) -> {
            for (SseEmitter em : list) {
                try {
                    em.send(SseEmitter.event().comment("hb"));
                } catch (IOException | IllegalStateException e) {
                    remove(username, em);
                }
            }
        });
    }

    private void remove(String username, SseEmitter em) {
        List<SseEmitter> list = connections.get(username);
        if (list != null) {
            list.remove(em);
            if (list.isEmpty()) connections.remove(username);
        }
    }

    @PreDestroy
    public void destroy() {
        heartbeatScheduler.shutdownNow();
    }
}
