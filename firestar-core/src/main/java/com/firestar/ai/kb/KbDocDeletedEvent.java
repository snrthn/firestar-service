package com.firestar.ai.kb;

/**
 * 文档删除完成事件（P1#5）：供向量化监听器同步清理 ES 向量，防孤儿向量残留。
 *
 * @param docId 已删除文档 id
 */
public record KbDocDeletedEvent(Long docId) {
}
