package com.firestar.realtime;

import org.springframework.context.ApplicationEvent;

/**
 * 实时推送领域事件。业务代码通过 ApplicationEventPublisher.publishEvent 发出，
 * 推送网关（SseRealtimeGateway）用 @EventListener 订阅，按 username 找到在线连接推送。
 * 这样业务模块与推送传输层完全解耦：审批/通知将来接入时，只需 publish 事件，零侵入网关。
 *
 * @param source  事件源（通常是 publish 所在的 Service 实例）
 * @param type    事件类型（见 RealtimeEventType）
 * @param username 目标用户；为 null 表示全站广播
 * @param payload 业务负载（任意可 JSON 序列化的对象，前端按 type 解析）
 */
public class RealtimeEvent extends ApplicationEvent {

    private final RealtimeEventType type;
    private final String username;
    private final Object payload;

    public RealtimeEvent(Object source, RealtimeEventType type, String username, Object payload) {
        super(source);
        this.type = type;
        this.username = username;
        this.payload = payload;
    }

    public RealtimeEventType getType() {
        return type;
    }

    public String getUsername() {
        return username;
    }

    public Object getPayload() {
        return payload;
    }
}
