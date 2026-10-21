package com.firestar.dto;

/**
 * 新建键值对请求（参数设置 → 键值管理）。
 * 用于后期扩展业务状态开关：运营侧自助新增系统级配置，
 * 不依赖改种子脚本 / 发版。
 */
public class ConfigCreateRequest {

    /** 配置键（必填，字母/数字/下划线/短横线，2-64 位，全库唯一） */
    private String configKey;

    /** 配置值（必填） */
    private String configValue;

    /** 说明（选填） */
    private String remark;

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
