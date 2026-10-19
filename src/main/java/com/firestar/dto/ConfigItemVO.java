package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 参数设置项视图对象。按 config_type 分组返回，前端按语义渲染表单，
 * 不暴露裸 key 作为存储细节（label 取 remark，回写用 configKey）。
 */
@Getter
@Setter
public class ConfigItemVO {

    /** 配置键（回写更新用） */
    private String configKey;

    /** 配置值 */
    private String configValue;

    /** 配置类型（字典 config_type） */
    private String configType;

    /** 说明（作为表单 label / 提示） */
    private String remark;
}
