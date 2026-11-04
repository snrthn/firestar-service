package com.firestar.dto;

import lombok.Data;

/**
 * 修改密码请求体（含首次登录强制改密场景）。
 */
@Data
public class ChangePasswordRequest {
    /** 原密码（首次改密时可为空，由 service 侧判断） */
    private String oldPassword;
    /** 新密码 */
    private String newPassword;
}
