package com.firestar.exception;

/**
 * 登录 IP 限流触发（爆破防护 L2）。由 {@code LoginProtectService.acquire} 在超出
 * 单 IP 窗口请求数时抛出，被 {@code LoginRateLimitFilter} 捕获并转换为 HTTP 429。
 *
 * <p>继承 RuntimeException，便于在 servlet Filter 中透传（Filter 不参与 Spring MVC 的
 * 异常解析，故由过滤器自行写 429 JSON）。
 */
public class LoginRateLimitException extends RuntimeException {

    public LoginRateLimitException(String message) {
        super(message);
    }
}
