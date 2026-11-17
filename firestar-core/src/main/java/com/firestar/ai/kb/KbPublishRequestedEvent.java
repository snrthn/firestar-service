package com.firestar.ai.kb;

import com.firestar.ai.entity.KbDoc;

/**
 * 发布请求事件（设计 §7.3 预留，发布门放行后、实际发布前发出）。
 *
 * <p>当前无消费者；后期 Flowable 审核流可监听本事件发起流程实例，
 * 回调后再决定是否继续发布。不监听不影响现有发布链路。</p>
 *
 * @param doc      待发布文档快照
 * @param operator 发布操作人
 */
public record KbPublishRequestedEvent(KbDoc doc, String operator) {
}
