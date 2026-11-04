package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 通知模板展示（管理端模板列表）。
 */
@Getter
@Setter
public class TemplateVO {

    private Long id;

    /** 模板编码：APPROVAL_TODO / SYSTEM_NOTICE ... */
    private String code;

    /** 模板名称（中文，下拉/列表展示用） */
    private String templateName;

    /** 通知类型 */
    private String type;

    /** 标题模板（{{变量}} 占位） */
    private String titleTpl;

    /** 正文模板（{{变量}} 占位） */
    private String contentTpl;

    /** 是否启用 */
    private Boolean enabled;

    private LocalDateTime updatedAt;
}
