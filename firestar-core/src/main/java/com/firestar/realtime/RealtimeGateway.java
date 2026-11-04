package com.firestar.realtime;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 实时推送网关接口（与具体传输方式解耦）。
 *
 * 当前实现：SseRealtimeGateway（基于 SseEmitter 的内存注册表，单 ECS 实例够用）。
 * 预留实现：RedisRealtimeGateway（Redis pub/sub 广播，多实例水平扩展时启用，
 *           只需 @Primary 切换，业务 / 事件发布代码零改动 —— 见 SseRealtimeGateway 头部说明）。
 */
public interface RealtimeGateway {

    /**
     * 注册一个 SSE 长连接（握手成功、JWT + current_jti 校验通过时调用）。
     * @return 交给 Controller 直接 return 给浏览器的 SseEmitter
     */
    SseEmitter connect(String username, String jti);

    /** 推给指定用户的全部在线连接（多标签页 = 多个连接，全部送达） */
    void pushToUser(String username, RealtimeEventType type, Object payload);

    /** 全站广播 */
    void broadcast(RealtimeEventType type, Object payload);
}
