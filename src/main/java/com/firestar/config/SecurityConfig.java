package com.firestar.config;

import com.firestar.common.JwtUtil;
import com.firestar.common.UnauthorizedResponses;
import com.firestar.service.TokenBlacklistService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全配置（认证模块）：JWT 无状态鉴权。
 *
 * <h3>匿名可访问（白名单）</h3>
 * <pre>
 *   /auth/login                 登录（唯一业务入口）
 *   /ping                       冒烟探测（只回 app + UP，无任何业务信息）
 *   /actuator/health/liveness   部署门禁探针（bin/start.sh、deploy.sh 轮询）
 *   /actuator/health/readiness  就绪探针
 *   /sse/**                     实时流，鉴权下沉到 SseEndpointSupport 自己裁决
 *   /error                      错误转发（否则业务报错会被伪装成 401）
 *   /favicon.ico
 *   —— 仅当 springdoc 启用时（开发/测试环境）—— 
 *   /doc.html、/swagger-ui/**、/v3/api-docs/**
 * </pre>
 * 其余全部需携带有效 JWT（由 JwtAuthenticationFilter 解析并查黑名单）。
 *
 * <h3>2026-10-19 安全收口（此前一律 permitAll 的三个接口）</h3>
 * 1. <b>/system/db-check</b> 不再匿名：返回体含库名 / 表名 / Flyway 版本 / 种子数据条数，
 *    属内部运维信息。改为「需有效 JWT」—— **登录即可，不再额外要求权限点**
 *    （曾加过 @RequirePerm("system:db-check")，属过度设计，已撤，理由见 DatabaseCheckController 类注释）。
 * 2. <b>/v3/api-docs、/swagger-ui、/doc.html</b> 不再无条件放行：仅当
 *    {@code springdoc.api-docs.enabled=true} 时进入白名单；生产/预发在对应 profile 里关闭
 *    springdoc，接口清单对外彻底消失。这里不再写死放行，是为了防止「关掉 springdoc 后
 *    白名单仍然把路径放给匿名」这种配置与代码各说各话的情形。
 * 3. <b>/actuator/health</b> 不再匿名：完整健康检查要认证后才能看。只保留
 *    liveness / readiness 两个**零信息披露**的探针匿名（{@code show-details=never}，
 *    返回体仅 {@code {"status":"UP"}}），否则 bin/start.sh 的部署门禁会 401 卡死。
 *    ⚠️ 运维命令 `curl /actuator/health` 现在要么带 Authorization，要么改用
 *    `/actuator/health/liveness`（门禁口径）。
 *
 * <h3>路径约定</h3>
 * ⚠️ 后端路由一律不带 /api 前缀（代理层剥离），白名单写的是后端实际收到的路径。
 *
 * <h3>错误语义</h3>
 * 401 = 未认证（该去登录），403 = 已认证但无权限。
 */
@Configuration
public class SecurityConfig {

    /** 任何环境都匿名可访问的路径（后端实际路径，不含 /api） */
    private static final String[] ALWAYS_PUBLIC = {
            "/auth/login",
            "/ping",
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
            "/sse/**",
            // 部署/监控探针：只报进程存活，show-details=never ⇒ 返回体仅 {"status":"UP"}。
            // 不能一起收进认证：bin/start.sh 与 deploy.sh 用 curl 轮询它做启动门禁，
            // 401 会被判成「启动超时」（见 start.sh 的超时诊断分支）。
            // ⚠️ 这里只放 liveness：application.properties 只配了 liveness 健康组，
            //    /actuator/health/readiness 并无对应分组（实测直接 404），别凭 k8s 习惯补一条。
            //    将来若新增 readiness 分组，记得同步加进本数组与 SecurityExposureTest。
            "/actuator/health/liveness"
    };

    /** 接口文档相关路径：只在 springdoc 开启的环境（dev/test）匿名可访问 */
    private static final String[] API_DOCS_PATHS = {
            "/doc.html",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs.yaml",
            "/v3/api-docs/**"
    };

    private final boolean apiDocsEnabled;

    public SecurityConfig(@Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) {
        this.apiDocsEnabled = apiDocsEnabled;
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil, TokenBlacklistService blacklistService,
                                                          com.firestar.service.PermissionResolver permissionResolver,
                                                          com.firestar.mapper.UserMapper userMapper) {
        return new JwtAuthenticationFilter(jwtUtil, blacklistService, permissionResolver, userMapper);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        List<String> publicPaths = new ArrayList<>(List.of(ALWAYS_PUBLIC));
        if (apiDocsEnabled) {
            publicPaths.addAll(List.of(API_DOCS_PATHS));
        }

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(publicPaths.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated()
                )
                // 未认证统一返 401，而不是 Spring Security 6 的默认 403。
                // 【为什么必须显式配】没有任何 formLogin/httpBasic 时，ExceptionTranslationFilter
                // 的默认入口点是 Http403ForbiddenEntryPoint —— 实测（2026-10-18 生产）未带 token
                // 请求任意受保护接口拿到的都是 **403 空响应体**，而前端 realtimeClient /
                // api/request.ts 全都按 401 判定会话失效，于是「未登录」被当成「无权限」，
                // SSE 侧表现为无限退避重连而不是弹「重新登录」。
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, authException) ->
                        UnauthorizedResponses.write(response, UnauthorizedResponses.MSG_EXPIRED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
