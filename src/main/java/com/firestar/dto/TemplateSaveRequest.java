package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 保存通知模板（管理端）。
 *
 * 允许改 template_name / title_tpl / content_tpl / enabled——
 * code 是业务接入的锚点，改了历史通知就对不上模板，不给改。
 * 改后只影响新通知，历史通知的成品文案已固化在 sys_notification，不改写。
 */
@Getter
@Setter
public class TemplateSaveRequest {

    private String templateName;

    private String titleTpl;

    private String contentTpl;

    private Boolean enabled;
}
