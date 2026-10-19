package com.firestar.controller;

import com.firestar.realtime.SseEndpointSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE（Server-Sent Events）实时推送控制器 —— **全站唯一的 SSE 路径前缀**。
 *
 * 【约定】所有 SSE 流都挂在 /sse/ 下，路径即业务名。这样做的价值在运维侧：
 * nginx 只需要一条 location /sse/（已配好 proxy_buffering off / gzip off /
 * proxy_cache off / 1h 超时 / X-Accel-Buffering: no），此后**新增任何一条流都不用再动
 * nginx 配置**。历史教训：每新增一条 SSE 流就复制一段 location，8 行配置重复 N 份，
 * 漏配任意一项都不会报错，只会让推送静默退化成「时好时坏」。
 *
 * 【鉴权】/sse/** 在 SecurityConfig 里 permitAll，真正的校验在 SseEndpointSupport 内部：
 * 缺 token / 过期 → 401 MSG_EXPIRED；被顶号 → 401 MSG_KICKED。
 * 让 SSE 自己决定如何在握手阶段表达「身份失效」，比在过滤器链上开特例更可控。
 *
 * 【扩展方式】加一个 @GetMapping 方法 + 调 support.serve(...) 即可，不用碰 nginx。
 *
 * 【路径约定】后端路由不带 /api（nginx 层的 /api/ location 会剥前缀）。本前缀例外：
 * /sse 直接暴露，nginx 为它单开了一条 location（proxy_pass 不带尾 URI，原始路径透传）。
 * 前端开发期经 vite 的 /sse proxy 转发，生产经 nginx 的 location /sse/，两条都对得上。
 */
@RestController
@RequestMapping("/sse")
public class SseController {

    private final SseEndpointSupport support;

    public SseController(SseEndpointSupport support) {
        this.support = support;
    }

    /**
     * 实时推送主通道 —— 一条连接承载全部 topic。
     *
     * 为什么不是「一个业务一条流」：用户开 5 个 Tab × 3 个 topic = 15 条并发长连接，
     * 而浏览器对同一域名的 HTTP/1.1 连接上限是 6 条，会互相阻塞、页面其它请求全排队。
     * 正解是单连接 + 消息体里的 event 名充当 topic，由前端 realtimeDispatch 分发。
     *
     * 握手成功后服务端会立刻发一个 CONNECTED 帧（见 SseRealtimeGateway.connect），
     * 让连接毫秒级可用，避免首帧迟到期间恰好发生的事件丢失。
     * 之后每 25s 一个心跳注释帧保活；连接上限 1h，到点由客户端重连。
     */
    @GetMapping(value = "/realtime", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object realtime(HttpServletRequest request) {
        return support.streamResponse(request);
    }

    /**
     * SSE 通路探针（运维自测用，非业务）。
     *
     * 存在的唯一目的：**验证「在 /sse/ 下新增一条流真的不用改 nginx」**。
     * 部署后用任意有效 token 请求 /sse/ping，若拿到 text/event-stream 且含 PONG 帧，
     * 说明 nginx 的 SSE 三关（proxy_buffering off / gzip off / proxy_cache off）都是对的；
     * 若拿到的是某个 HTML 页面，说明 nginx 那边还没配上，别去后端找原因。
     *
     * 行为：握手校验通过后发一帧 PONG 并立即结束连接（不占用长连接资源）。
     */
    @GetMapping(value = "/ping", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object ping(HttpServletRequest request) {
        return support.serve(request, (username, jti) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("username", username);
            data.put("serverTime", Instant.now().toString());
            // ⚠️ SseEmitter 必须裸返回，不能包 ResponseEntity
            //（包了会 HttpMessageNotWritableException: No converter for SseEmitter，
            //  详见 SseEndpointSupport.serve 的注释）
            return SseEndpointSupport.oneShot(10_000L, "PONG", data);
        });
    }
}
