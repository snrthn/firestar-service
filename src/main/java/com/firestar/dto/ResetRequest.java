package com.firestar.dto;

import lombok.Data;

/**
 * 系统重置请求。
 * confirmText：必须输入确认词 RESET（二次确认，防误操作）。
 * verifyCode：验证码。首次提交（获取验证码）时为空，后端生成并回传；
 *            二次提交（执行重置）时携带该码，校验通过才真正执行。
 */
@Data
public class ResetRequest {
    private String confirmText;
    private String verifyCode;
}
