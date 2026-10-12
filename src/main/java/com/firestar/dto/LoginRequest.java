package com.firestar.dto;

import lombok.Data;

/**
 * 登录请求体。
 */
@Data
public class LoginRequest {
    /** 用户名/账号 */
    private String username;
    /** 明文密码（传输层建议 HTTPS；服务端仅做 BCrypt 校验，不落库） */
    private String password;
}
