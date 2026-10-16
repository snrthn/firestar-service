package com.firestar.exception;

import com.firestar.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * ⚠️ ResponseStatusException 必须显式处理并保留原 HTTP 状态码。
     * 它是 RuntimeException 子类，会被兜底 Exception handler 收走 → 变成
     * HTTP 200 + {"code":500} 壳（状态语义丢失）。2026-10-16 线上事故：
     * /auth/stream 的 401（token 过期/被顶号）被吞成 200+500 壳，前端 SSE 客户端
     * 把 200 当流读、壳里没有 event: 行被静默忽略 → 流关闭 → 2s 重连 → 死循环。
     * 用 ResponseEntity 而不是 Result，才能把 401 状态码带回响应。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> handleStatus(ResponseStatusException e) {
        int status = e.getStatusCode().value();
        String msg = e.getReason() != null ? e.getReason() : "请求处理失败";
        return ResponseEntity.status(status).body(Result.error(status, msg));
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.error(500, "服务器内部错误");
    }
}
