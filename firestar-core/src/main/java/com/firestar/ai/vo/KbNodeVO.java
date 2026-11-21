package com.firestar.ai.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 节点骨架 VO：严格对齐前端 {@code api/kb.ts} 的 KbNode。
 * <b>绝不含 contentMd</b> —— 全量树接口只下发轻量元数据，正文由 /docs/{id} 单独拉。
 */
@Getter
@Setter
public class KbNodeVO {

    private Long id;
    private Long parentId;
    private String title;
    /** DOC | FOLDER */
    private String nodeType;
    private Integer sortNo;
    private String path;
    private String status;
    private Integer version;
    private Boolean hasChild;
    private String aclRoles;
    private LocalDateTime updatedAt;
    /** 已发布节点是否留有未重新发布的保存修改（树节点状态点用） */
    private Boolean hasUnpublishedEdits;
}
