package com.firestar.ai.vo;

import lombok.Getter;
import lombok.Setter;

/** 新建节点请求：对齐前端 KbNodeCreate */
@Getter
@Setter
public class KbNodeCreateReq {

    /** 0 = 根目录 */
    private Long parentId;
    private String title;
    /** DOC | FOLDER */
    private String nodeType;
    /** 不传则由后端按同级最大 +10 计算 */
    private Integer sortNo;
}
