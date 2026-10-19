package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 关于我们弹窗聚合数据（登录用户可见）。
 * 字段即前端展示项，不暴露 config 存储细节。
 */
@Getter
@Setter
public class AboutVO {

    /** 系统名称 */
    private String systemName;

    /** ICP 备案号 */
    private String icpNo;

    /** 技术支持联系方式 */
    private String supportContact;

    /** 关于我们（富文本） */
    private String aboutUs;

    /** 应用版本号 */
    private String appVersion;
}
