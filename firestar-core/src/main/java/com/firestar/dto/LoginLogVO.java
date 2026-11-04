package com.firestar.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录日志视图对象（列表/详情展示用，剥离审计冗余列）。
 */
@Data
public class LoginLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String username;
    /** 结果（字典 login_status）：SUCCESS / FAIL / KICKED_OUT */
    private String loginStatus;
    private String ipAddress;
    private String userAgent;
    private String failReason;
    private LocalDateTime loginAt;
}
