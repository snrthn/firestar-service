package com.firestar.dto;

import lombok.Data;

/**
 * 当前用户自助更新资料请求体（仅昵称 / 手机号 / 邮箱，本人可改）。
 * 用户名等账号标识不可改；密码走独立的 /auth/password。
 */
@Data
public class ProfileUpdateRequest {
    /** 昵称（必填） */
    private String nickname;
    /** 手机号（可空，填了需 11 位） */
    private String phone;
    /** 邮箱（可空，填了需符合邮箱格式） */
    private String email;
}
