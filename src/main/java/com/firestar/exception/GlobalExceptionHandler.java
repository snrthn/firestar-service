package com.firestar.exception;

import com.firestar.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。注意：JWT 校验失败发生在 Filter 阶段（早于 DispatcherServlet），
 * 不会经过本处理器，由 JwtAuthenticationFilter 直接写 401 JSON。本处理器覆盖：
 *   - BizException（业务异常，透传 message）
 *   - AuthenticationException（Spring Security 在 Controller 层抛出的鉴权异常 → 401）
 *   - 兜底 Exception（500，避免堆栈外泄）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public Result<Void> handleAuth(AuthenticationException e) {
        return Result.error(401, "未登录或登录已过期");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.error(500, "服务器内部错误");
    }
}
