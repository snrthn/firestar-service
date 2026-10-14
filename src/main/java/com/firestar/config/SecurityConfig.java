package com.firestar.config;

import com.firestar.common.JwtUtil;
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
                                                          com.firestar.service.PermissionResolver permissionResolver) {
        return new JwtAuthenticationFilter(jwtUtil, blacklistService, permissionResolver);
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
                                "/favicon.ico"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
