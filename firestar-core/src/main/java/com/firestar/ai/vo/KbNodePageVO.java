package com.firestar.ai.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 节点页 VO：对齐前端 KbNodePage。
 * 飞书 wiki node list 契约：pageToken 是游标（本实现为十进制 offset，前端原样回传），
 * hasMore=true 时 items 可能为空（ACL 过滤造成），<b>前端不得把空列表当作无子节点</b>。
 */
@Getter
@Setter
public class KbNodePageVO {

    private List<KbNodeVO> items;
    private Boolean hasMore;
    private String nextPageToken;
    /** 节点规模超阈值，前端应切为按 parentId 懒加载 */
    private Boolean lazyRequired;
}
