package com.firestar.exception;

import com.firestar.common.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全局异常处理器状态码门禁（纯单元测试，不连库、无需 Spring 上下文）。
 *
 * <p>为什么需要（2026-11-05 实锤）：{@link GlobalExceptionHandler} 的
 * {@code @ExceptionHandler(Exception.class)} 兜底优先级高于 Spring 默认的
 * DefaultHandlerExceptionResolver，会把本应转 400/405 的异常吞成
 * HTTP 200 + {"code":500} 壳，真实错误语义丢失、前端按 code=500 误报「服务器内部错误」。
 * 404 / ResponseStatusException 已显式处理，本测试补齐 405 / 400 两条，
 * 并钉死「handler 真的把正确状态码带回 ResponseEntity」。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("405：HttpRequestMethodNotSupportedException 必须返回 405 + code=405 + 可读提示")
    void methodNotSupportedReturns405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET", java.util.List.of("POST"));

        ResponseEntity<Result<Void>> resp = handler.handleMethodNotSupported(ex);

        assertEquals(405, resp.getStatusCode().value(), "HTTP 状态码必须 405");
        Result<Void> body = Objects.requireNonNull(resp.getBody());
        assertEquals(405, body.getCode(), "body.code 必须 405（与 HTTP 一致）");
        assertEquals("不支持的请求方法：GET（支持：POST）", body.getMessage());
    }

    @Test
    @DisplayName("400：HttpMessageNotReadableException 必须返回 400 + code=400 + 可读提示")
    void messageNotReadableReturns400() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("boom", (Throwable) null);

        ResponseEntity<Result<Void>> resp = handler.handleMsgNotReadable(ex);

        assertEquals(400, resp.getStatusCode().value(), "HTTP 状态码必须 400");
        Result<Void> body = Objects.requireNonNull(resp.getBody());
        assertEquals(400, body.getCode(), "body.code 必须 400（与 HTTP 一致）");
        assertEquals("请求体格式错误或无法解析，请检查 JSON 结构与字段类型", body.getMessage());
    }

    @Test
    @DisplayName("回归：兜底 Exception handler 仍返回 500（用于对照，证明两个新方法不会误吞其他异常）")
    void fallbackStillReturns500() {
        Result<Void> body = handler.handleOther(new RuntimeException("unexpected"));
        assertEquals(500, body.getCode());
    }
}
