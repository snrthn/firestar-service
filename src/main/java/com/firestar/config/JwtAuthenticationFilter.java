package com.firestar.config;

import com.firestar.common.JwtUtil;
import com.firestar.common.UnauthorizedResponses;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.service.PermissionResolver;
import com.firestar.service.TokenBlacklistService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 认证过滤器：从 Authorization: Bearer <token> 解析出用户与角色，写入 Spring Security 上下文。
 * 同时查 Token 黑名单（logout 后失效）。无 token 的请求直接放行，由 SecurityConfig 的
 * authorizeHttpRequests 决定是否需要认证（白名单匿名、其余 authenticated）。
 * 解析失败 / 黑名单命中 → 直接写 401 JSON，不再向下传递。
 *
 * 权限注入：除角色 authority 外，额外把当前用户拥有的 perm_code 以 {@code PERM:<code>}
 * 形式写入 authorities，供 PermissionInterceptor 做 API 级权限拦截。权限实时查库（不缓存，
 * 保证权限变更即时生效）。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** authorities 中权限点的前缀，避免与角色名（roleCode）冲突 */
    public static final String PERM_AUTHORITY_PREFIX = "PERM:";

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService blacklistService;
    private final PermissionResolver permissionResolver;
    private final UserMapper userMapper;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, TokenBlacklistService blacklistService,
                                   PermissionResolver permissionResolver, UserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.blacklistService = blacklistService;
        this.permissionResolver = permissionResolver;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                if (blacklistService.isBlacklisted(jwtUtil.getJti(token))) {
                    writeUnauthorized(response, "登录已失效，请重新登录");
                    return;
                }
                Claims claims = jwtUtil.parse(token);
                String username = claims.getSubject();
                // 单点登录：校验 token 的 jti 是否仍是该用户「当前有效会话」。
                // current_jti 为 null 视为尚未纳入管控（迁移前已登录的旧会话），放行；
                // 一旦该用户重新登录写入 current_jti，旧会话即因 jti 不匹配被踢。
                //
                // ⚠️ 必须同时查 status（2026-10-19 补）：此前只查 current_jti，
                // 管理员在后台把账号停用（DISABLED）后，该用户手里未过期的 token
                // 仍能继续访问 2 小时 —— 「停用」形同虚设。这里把状态判定并进同一次查询，
                // 换来的代价是零（原本就要查这一行）。
                // ⚠️ select 里必须带上恒非空的 username：只选 current_jti 时该列为 NULL
                // 会导致 MyBatis 不创建行对象（returnInstanceForEmptyRow=false）→
                // 「用户存在」被误判成「用户不存在」（同类坑见 SseEndpointSupport 注释）。
                String jti = claims.getId();
                User sessionUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
                        .select(User::getUsername, User::getStatus, User::getCurrentJti));
                if (sessionUser == null) {
                    // 用户已被物理删除，token 立即作废（旧实现是「查不到就放行」）
                    writeUnauthorized(response, "用户不存在或登录已失效");
                    return;
                }
                if (!"ENABLED".equals(sessionUser.getStatus())) {
                    writeUnauthorized(response, "账号已被禁用，请联系管理员");
                    return;
                }
                if (sessionUser.getCurrentJti() != null
                        && !sessionUser.getCurrentJti().equals(jti)) {
                    writeUnauthorized(response, "您的账号已在其他设备登录，原会话已失效，请重新登录");
                    return;
                }
                List<String> roles = jwtUtil.getRoles(token);
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                if (roles != null) {
                    roles.stream().map(SimpleGrantedAuthority::new).forEach(authorities::add);
                    // 实时解析权限集合，以 PERM:<code> 写入 authorities（供 PermissionInterceptor 校验）
                    for (String permCode : permissionResolver.resolve(roles)) {
                        authorities.add(new SimpleGrantedAuthority(PERM_AUTHORITY_PREFIX + permCode));
                    }
                }
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        username, null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException e) {
                writeUnauthorized(response, "Token 无效或已过期");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 写 401 JSON。
     * ⚠️ 必须走 UnauthorizedResponses，保证与 SecurityConfig 的 authenticationEntryPoint、
     * SseEndpointSupport 三处产出**完全相同**的 JSON 结构
     *（{"code":401,"message":...,"data":null}）—— 历史上三处各写各的，
     * 一旦漂移前端 res.json() 就解析不到 message，只能回落兜底文案。
     */
    private void writeUnauthorized(HttpServletResponse response, String msg) throws IOException {
        UnauthorizedResponses.write(response, msg);
    }
}
