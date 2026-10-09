package com.firestar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 占位安全配置 —— 空壳阶段放行所有请求，便于启动与 /actuator/health 健康检查。
 *
 * TODO: 按技术栈文档接入 JWT + RBAC（Spring Security 6 资源服务器）后整体替换本类。
 *       届时需对 /api/** 受保护资源做鉴权，仅放行 /actuator/health、登录等白名单。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
