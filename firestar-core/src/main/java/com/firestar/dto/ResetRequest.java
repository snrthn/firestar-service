package com.firestar.dto;

import lombok.Data;

/**
 * 系统重置请求。
 * confirmText：必须输入确认词 RESET（二次确认，防误操作）。
 * verifyCode：验证码。首次提交（获取验证码）时为空，后端生成并回传；
 *            二次提交（执行重置）时携带该码，校验通过才真正执行。
 * password：当前管理员登录密码（P2 加固）。仅在执行重置（携带 verifyCode）时校验，
 *          防止仅凭管理员会话即可清库。通道当前受 trialMode 关闭保护，属纵深防御。
 */
@Data
public class ResetRequest {
    private String confirmText;
    private String verifyCode;
    private String password;
}
