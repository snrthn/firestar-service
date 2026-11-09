package com.firestar.ai.vo;

import lombok.Getter;
import lombok.Setter;

/** 移动节点请求：对齐前端 moveKbNode 的 payload */
@Getter
@Setter
public class KbNodeMoveReq {

    /** 0 = 移到根目录 */
    private Long parentId;
    private Integer sortNo;
}
