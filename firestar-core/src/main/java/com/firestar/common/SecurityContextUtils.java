package com.firestar.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具：当前登录用户名 + 超级管理员判定。
 *
 * <p>数据可见性场景（2026-10-22 虎哥：发起类审批数据只有自己能看到自己发起的，
 * ROLE_ADMIN 无条件放行）：列表查询按 {@link #isAdmin()} 决定是否追加
 * 「发起人 = 当前用户」行级过滤。判定信号与 PermissionInterceptor 的 API 层放行
 * 完全一致——authorities 中 role_code='ROLE_ADMIN'（sys_role.role_code 直灌
 * authority），零查库、零迁移。</p>
 *
 * <p>约定：后续新增任何「我发起的」语义的查询，一律沿用本范式
 * （username + viewAll 双参 + 非 admin 按发起人列过滤），不要另起炉灶。</p>
 *
 * <p><b>2026-10-24 强化</b>：过滤动作本身收进了 {@link DataScopeHelper}
 * （{@code DataScopeHelper.own(wrapper, 归属列, 归属值)}），归属列对照表与完整落地步骤
 * 见 {@code docs/开发规范.md} 第四节。本类只负责提供 username / isAdmin 两个信号。</p>
 */
public final class SecurityContextUtils {

    private static final String ADMIN_ROLE_CODE = "ROLE_ADMIN";

    private SecurityContextUtils() {
    }

    /** 当前登录用户名（JWT subject）；取不到时抛 IllegalStateException */
    public static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new IllegalStateException("无法获取当前登录用户");
        }
        return auth.getName();
    }

    /** 是否超级管理员（ROLE_ADMIN）：数据行级隔离的无条件放行信号 */
    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ADMIN_ROLE_CODE.equals(a.getAuthority()));
    }
}
