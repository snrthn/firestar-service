package com.firestar.ai.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 文档详情 VO：对齐前端 KbDocDetail，是唯一返回 contentMd 的接口。 */
@Getter
@Setter
public class KbDocDetailVO {

    private Long id;
    private String title;
    private String summary;
    private String contentMd;
    private String status;
    private Integer version;
    private String aclRoles;
    private Boolean egressAllowed;
    private LocalDateTime updatedAt;
    /** 已发布文档是否留有未重新发布的保存修改（后端权威计算，前端只读） */
    private Boolean hasUnpublishedEdits;
}
