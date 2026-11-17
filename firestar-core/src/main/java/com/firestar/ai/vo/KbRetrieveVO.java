package com.firestar.ai.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 知识库检索调试结果 VO：一次检索的元信息 + 命中明细列表。
 * 与线上 search_knowledge 走同一个 {@code KbRetriever}，保证「调试看到的」=「模型实际拿到的」。
 */
@Getter
@Setter
public class KbRetrieveVO {

    /** 原始查询词 */
    private String query;
    /** 实际生效的 topK（<=0 取默认 4，上限 8） */
    private int topK;
    /** 命中数（已过 ACL + 出域策略） */
    private int total;
    /** 检索耗时（毫秒），便于观察性能 */
    private long elapsedMs;
    private List<KbRetrieveHitVO> hits;
}
