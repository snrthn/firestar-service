package com.firestar.config;

import com.firestar.common.JwtUtil;
import com.firestar.common.UnauthorizedResponses;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.service.PermissionResolver;
import com.firestar.service.TokenBlacklistService;
import com.firestar.service.UserRoleService;
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
 * JWT 认证过滤器：从 Authorization: Bearer <token> 解析出用户，写入 Spring Security 上下文。
 * 同时查 Token 黑名单（logout 后失效）。无 token 的请求直接放行，由 SecurityConfig 的
 * authorizeHttpRequests 决定是否需要认证（白名单匿名、其余 authenticated）。
 * 解析失败 / 黑名单命中 → 直接写 401 JSON，不再向下传递。
 *
 * 角色来源（2026-10-23 改）：不再读 token 里的 roles 快照，而是按 token 的 username
 * 实时查 user_role + sys_role 拿 roleCode。这样「变更用户角色」在下次请求即生效，
 * 无需重登（菜单本就实时，此举让数据隔离/拦截器与菜单同源）；同时堵住
 * 「减 ROLE_ADMIN 后菜单秒隐但 isAdmin()/超管捷径仍为真」的减权不生效洞。
 * 权限点（PERM:<code>）仍由 PermissionResolver 每次实时解析。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** authorities 中权限点的前缀，避免与角色名（roleCode）冲突 */
    public static final String PERM_AUTHORITY_PREFIX = "PERM:";

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService blacklistService;
    private final PermissionResolver permissionResolver;
    private final UserMapper userMapper;
    private final UserRoleService userRoleService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, TokenBlacklistService blacklistService,
                                   PermissionResolver permissionResolver, UserMapper userMapper,
                                   UserRoleService userRoleService) {
        this.jwtUtil = jwtUtil;
        this.blacklistService = blacklistService;
        this.permissionResolver = permissionResolver;
        this.userMapper = userMapper;
        this.userRoleService = userRoleService;
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
                // 2026-10-23 增补 User::getId：供下方 UserRoleService 按 userId 实时查角色。
                String jti = claims.getId();
                User sessionUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
                        .select(User::getId, User::getUsername, User::getStatus, User::getCurrentJti));
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
                // 角色来源：实时查库（不再读 token 的 roles 快照）。
                // 变更用户角色后，下次请求即按新角色重建 authorities（无需重登）。
                List<String> roles = userRoleService.findRoleCodesByUserId(sessionUser.getId());
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
