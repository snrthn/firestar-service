package com.firestar.realtime;

/**
 * 实时推送事件类型。业务侧只关心 type，推送网关按 type 路由到前端对应处理器。
 *
 * 当前实现：FORCE_LOGOUT（互踢，立即可落）。
 * 预留：APPROVAL_CREATED / APPROVAL_DONE（Flowable 审批流）/ NOTIFY（通用通知）。
 * 后续业务接入时只需在此追加枚举值 + 前端 realtimeDispatch 加 case，不动网关。
 */
public enum RealtimeEventType {
    FORCE_LOGOUT,
    APPROVAL_CREATED,
    APPROVAL_DONE,
    NOTIFY
}
