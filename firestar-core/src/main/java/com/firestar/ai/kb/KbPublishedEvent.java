package com.firestar.ai.kb;

import com.firestar.ai.entity.KbDoc;

/**
 * 发布完成事件（设计 §7.3 预留，发布事务内切片完成后发出）。
 *
 * <p>当前切片同步在 KbDocService.publish() 内完成，本事件暂无消费者；
 * 后期可让 re-chunk / 向量化 / 缓存刷新等监听器订阅，解耦发布主流程。</p>
 *
 * @param docId      已发布文档 id
 * @param version    已发布版本
 * @param chunkCount 本次落库切片数
 */
public record KbPublishedEvent(Long docId, int version, int chunkCount) {
}
