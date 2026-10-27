package com.firestar.exception;

import com.firestar.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * IllegalArgumentException 透传 message（400）——引擎层/业务层的参数类校验
     * （如「任务不存在或已结束」「驳回目标节点非法」）抛的是裸 IAE，没有专门 handler
     * 时会落进兜底 Exception → HTTP 200 + {"code":500,"message":"服务器内部错误"}，
     * 真实原因被吞，用户只看得到「服务器内部错误」（2026-10-22 虎哥实测：
     * 审批点通过报 500，实际是旧 taskId 被重新提交终结）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArg(IllegalArgumentException e) {
        return Result.error(400, e.getMessage());
    }

    /**
     * ⚠️ DuplicateKeyException（唯一键冲突，如手填重复发票号/单据号）必须显式处理。
     * 它是 RuntimeException 子类，会落入兜底 handler -> HTTP 200 + {"code":500,"message":"服务器内部错误"}，
     * 真实原因（撞唯一键）被吞，用户只见"服务器内部错误"无法定位是填了重复号（2026-10-27 实锤）。
     * 业务层（create/update）已做精确查重并抛 IAE 友好提示，此处是最后兜底：
     * 任何唯一键冲突都不应表现为 500，统一转 400 并给出可读提示。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突（已兜底为 400）：{}", e.getMessage());
        return Result.error(400, "单据编号重复，请检查唯一编号后重试");
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

    /**
     * ⚠️ 404 必须显式处理，否则会被下面的兜底 handler 收走 →
     * HTTP 200 + {"code":500,"message":"服务器内部错误"}。
     *
     * <p>这条是实测发现的真问题（2026-10-19）：请求一个不存在的路径时，
     * Spring 6.1 的静态资源处理器抛 {@link NoResourceFoundException}，
     * 它是 RuntimeException 子类，正好落进 {@code @ExceptionHandler(Exception.class)}。
     * 后果有三个：
     * <ol>
     *   <li>调用方拿到的不是 404：前端按 body.code 弹「服务器内部错误」，
     *       排查时会一直怀疑后端崩了，而其实是路径打错；</li>
     *   <li>网关 / 监控 / CI 的 {@code curl -f} 看不出「路径不存在」，全按 200 成功处理；</li>
     *   <li>安全层面的误导：扫描器探测不存在的管理端路径，也会收到"服务正常"的 200。</li>
     * </ol>
     * 用 ResponseEntity 而不是 Result，才能把 404 状态码带回响应
     * （与 {@link ResponseStatusException} 那条同一个道理）。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(404, "接口不存在"));
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.error(500, "服务器内部错误");
    }
}
