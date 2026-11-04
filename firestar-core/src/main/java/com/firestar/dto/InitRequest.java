package com.firestar.dto;

import lombok.Data;

/**
 * 初始化安装向导请求。
 * 管理员密码 {@code adminPassword} 必填（由用户自行设置，不再使用默认 admin123）；
 * 管理员账号 {@code adminUsername} 留空时回退默认 admin，昵称留空时回退「系统管理员」。
 */
@Data
public class InitRequest {
    /** 超管账号，默认 admin */
    private String adminUsername;
    /** 超管初始密码，默认 admin123（首次登录强制改密） */
    private String adminPassword;
    /** 超管昵称，默认 系统管理员 */
    private String adminNickname;
}
