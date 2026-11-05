package com.firestar.config;

import com.firestar.common.IpUtil;
import com.firestar.exception.LoginRateLimitException;
import com.firestar.service.LoginProtectService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * 登录 IP 限流过滤器（爆破防护 L2）。
 *
 * <p><b>关键</b>：必须位于 Spring Security 认证链<b>之前</b>，才能在错误密码到达
 * {@code AuthServiceImpl} 之前拦截喷射请求（社区踩坑点：放认证链之后只能看到 401，对密码
 * 猜测零作用）。通过 {@code FilterRegistrationBean} 仅拦截 {@code /auth/login}，并以最高优先级
 * 注册，使其早于 SecurityFilterChainProxy 执行。
 *
 * <p>真实 IP 取自 {@link IpUtil#getClientIp}（优先 X-Forwarded-For）。⚠️ XFF 可被伪造，仅作
 * 近似来源识别、不用于鉴权；高安全场景应叠加边缘 WAF（调研文档 L6）。
 */
@Slf4j
public class LoginRateLimitFilter implements Filter {

    private final LoginProtectService protectService;

    public LoginRateLimitFilter(LoginProtectService protectService) {
        this.protectService = protectService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        if (isLoginPath(req.getRequestURI()) && "POST".equalsIgnoreCase(req.getMethod())) {
            String ip = IpUtil.getClientIp(req);
            try {
                protectService.acquire(ip);
            } catch (LoginRateLimitException e) {
                HttpServletResponse resp = (HttpServletResponse) response;
                resp.setStatus(429);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write("{\"code\":429,\"message\":\"" + e.getMessage() + "\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isLoginPath(String uri) {
        return uri != null && uri.endsWith("/auth/login");
    }
}
