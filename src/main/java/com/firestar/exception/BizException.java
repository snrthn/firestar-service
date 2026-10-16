package com.firestar.exception;

import lombok.Getter;

/**
 * 业务异常：在 Service 层抛出，由 GlobalExceptionHandler 统一捕获并透传 message。
 * 用法：throw new BizException("用户名或密码错误"); 或 throw new BizException(40001, "...");
 */
public class BizException extends RuntimeException {

    @Getter
    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(500, message);
    }

    /** 带 cause：透传底层异常（如 OSS SDK 的网络错误），保证堆栈链完整可查 */
    public BizException(String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
    }
}
