package com.firestar.config;

import com.firestar.common.JwtUtil;
import com.firestar.common.UnauthorizedResponses;
import com.firestar.service.TokenBlacklistService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置（认证模块）：JWT 无状态鉴权。
 *
 * 白名单（无需 token）：
 *   /auth/login        登录
 *   /ping              冒烟
 *   /system/db-check   运维自检（⚠️ 含库名表名，接运维权限后应收窄，当前仅本地诊断便利）
 *   /actuator/health   健康检查
 *   /doc.html /swagger-ui/** /v3/api-docs/**  接口文档
 *   /design-audit/**   临时设计审计静态页
 *   /favicon.ico
 * 其余全部需携带有效 JWT（由 JwtAuthenticationFilter 解析并查黑名单）。
 *
 * ⚠️ 后端路由一律不带 /api 前缀（代理层剥离），白名单写的是后端实际收到的路径。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil, TokenBlacklistService blacklistService,
                                                          com.firestar.service.PermissionResolver permissionResolver,
                                                          com.firestar.mapper.UserMapper userMapper) {
        return new JwtAuthenticationFilter(jwtUtil, blacklistService, permissionResolver, userMapper);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/auth/login",
                                "/ping",
                                "/system/db-check",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/doc.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/design-audit/**",
                                "/favicon.ico",
                                // 错误转发。2026-10-19 本地实测发现：任何未捕获异常（含 404）都会
                                // 转发到 /error，而它对匿名请求同样走 anyRequest().authenticated()
                                // → 被 entryPoint 拦成 401（改配置前是 403 空响应体）。
                                // 后果是「接口报错」被伪装成「未登录」：前端按 401 弹「重新登录」，
                                // 真正的错误信息（415/500/404）全被吞掉，排查时会一直查错方向。
                                // 放行 /error 后，原始错误状态码才能透出。
                                "/error",
                                // SSE 统一前缀：鉴权下沉到端点内部自己裁决（见 SseEndpointSupport）。
                                // 放行到 Controller 而不是在过滤器链上开特例，是为了让 SSE 能自己决定
                                // 握手阶段如何表达「身份失效」——缺 token 与 token 过期要能用
                                // 不同的 message 区分，前端 realtimeClient 靠它区分「重新登录」与「被顶号」。
                                // ⚠️ 代价：/sse/** 下的端点必须自己调 SseEndpointSupport.serve(...) 做校验，
                                // 直接返回数据就等于是公开接口。
                                "/sse/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                // 未认证统一返 401，而不是 Spring Security 6 的默认 403。
                // 【为什么必须显式配】没有任何 formLogin/httpBasic 时，ExceptionTranslationFilter
                // 的默认入口点是 Http403ForbiddenEntryPoint —— 实测（2026-10-18 生产）未带 token
                // 请求任意受保护接口拿到的都是 **403 空响应体**，而前端 realtimeClient /
                // api/request.ts 全都按 401 判定会话失效，于是「未登录」被当成「无权限」，
                // SSE 侧表现为无限退避重连而不是弹「重新登录」。
                // 语义约定：401 = 未认证（该去登录），403 = 已认证但无权限。
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, authException) ->
                        UnauthorizedResponses.write(response, UnauthorizedResponses.MSG_EXPIRED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
