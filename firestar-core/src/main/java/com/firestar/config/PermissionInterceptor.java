package com.firestar.config;

import com.firestar.common.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API 级权限拦截器（方案 B：全局 HandlerInterceptor）。
 *
 * 执行时机：在 Spring Security 过滤器链之后、DispatcherServlet 内部，故此时
 * JwtAuthenticationFilter 已把角色 + 权限（PERM:&lt;code&gt;）写入 SecurityContext，
 * 本拦截器只做比对，不再查库。
 *
 * 规则：
 *   1. 白名单路径（同 SecurityConfig）直接放行，不做权限校验；
 *   2. 解析 handler 上的 @RequirePerm（方法优先于类）；无注解 → 视为公开接口，放行；
 *   3. ROLE_ADMIN 超级管理员放行（保险网，ROLE_ADMIN 本就拥有全部权限点）；
 *   4. 所需权限点任一不在 authorities 中 → 返回 HTTP 200 + Result{code=403, message}，
 *      与项目「业务拒绝 HTTP 仍是 200、看 body.code」约定一致，前端响应拦截会弹出 message。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    /**
     * 白名单：与 SecurityConfig.ALWAYS_PUBLIC 保持一致。
     *
     * <p>2026-10-19 安全收口时移除了四项，别再「顺手加回来」：
     * <ul>
     *   <li>{@code /system/db-check} —— 现在要有效 JWT（登录即可，类上不再有 @RequirePerm）。
     *       它必须留在白名单外：白名单优先返回 true，会绕过 {@code anyRequest().authenticated()}，
     *       等于把它重新变成匿名接口；</li>
     *   <li>{@code /actuator/health} —— 完整健康检查改为需认证，只留 liveness/readiness；</li>
     *   <li>{@code /doc.html}、{@code /swagger-ui/}、{@code /v3/api-docs/} —— 接口文档
     *       由 profile 决定是否开启，本拦截器不再单独放行（这些是静态资源/非 Controller
     *       处理器，本来也不会命中 @RequirePerm 校验）；</li>
     *   <li>{@code /design-audit/} —— 早期临时静态页，目录早已不存在，属僵尸白名单。</li>
     * </ul>
     */
    private static final Set<String> EXACT_WHITELIST = Set.of(
            "/auth/login",
            "/ping",
            "/favicon.ico",
            // 部署门禁探针（只回 {"status":"UP"}，show-details=never）
            "/actuator/health/liveness"
    );
    private static final Set<String> PREFIX_WHITELIST = Set.of(
            "/sse/"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        if (isWhitelisted(path)) {
            return true;
        }

        // 解析所需权限点
        String[] required = resolveRequiredPerms(handler);
        if (required == null || required.length == 0) {
            // 未标注 @RequirePerm → 公开接口（只需 JWT，SecurityConfig 已保证认证）
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            // 防御性：理论上 SecurityConfig 已拦截未认证请求
            writeForbidden(response);
            return false;
        }

        // ROLE_ADMIN 超级管理员放行
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) {
            return true;
        }

        // 收集当前用户权限点
        Set<String> owned = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(JwtAuthenticationFilter.PERM_AUTHORITY_PREFIX))
                .map(a -> a.substring(JwtAuthenticationFilter.PERM_AUTHORITY_PREFIX.length()))
                .collect(Collectors.toCollection(HashSet::new));

        boolean allowed = Arrays.stream(required).anyMatch(owned::contains);
        if (allowed) {
            return true;
        }
        writeForbidden(response);
        return false;
    }

    private String[] resolveRequiredPerms(Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return null;
        }
        RequirePerm methodAnn = hm.getMethod().getAnnotation(RequirePerm.class);
        if (methodAnn != null) {
            return methodAnn.value();
        }
        RequirePerm classAnn = hm.getMethod().getDeclaringClass().getAnnotation(RequirePerm.class);
        if (classAnn != null) {
            return classAnn.value();
        }
        return null;
    }

    private boolean isWhitelisted(String path) {
        if (EXACT_WHITELIST.contains(path)) {
            return true;
        }
        for (String prefix : PREFIX_WHITELIST) {
            if (path.startsWith(prefix)) {
                // /sse/ 前缀整体放行：其余 SSE 端点（/sse/realtime、/sse/ping）走 SseEndpointSupport
                // 内部鉴权，不依赖 @RequirePerm（见 SseController 类注释）。
                // 但 /sse/ai/** 是 AI 对话流，走 JWT 过滤器 + @RequirePerm("ai:assistant:use")
                // 接口权限，必须过本拦截器校验，故在此排除 —— 否则 @RequirePerm 形同虚设。
                if (path.startsWith("/sse/ai/")) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private void writeForbidden(HttpServletResponse response) throws java.io.IOException {
        // 与项目约定一致：HTTP 200 + Result{code=403}，前端响应拦截按 body.code!=0 弹 message
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"无访问权限\",\"data\":null}");
    }
}
