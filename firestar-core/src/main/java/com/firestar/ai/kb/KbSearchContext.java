package com.firestar.ai.kb;

import java.util.Set;

/**
 * 检索上下文：当前请求用户的可见性身份（复用 {@code KbTreeRules.isVisible} 口径做 ACL 过滤）。
 *
 * @param admin      是否管理员（ROLE_ADMIN 无条件放行）
 * @param authorities 当前用户权限集合（role_code 直灌 / PERM:xxx；与 KbDocService 计算口径一致）
 * @param tenantId   租户 id（P0 固定 0）
 */
public record KbSearchContext(boolean admin, Set<String> authorities, Long tenantId) {
}
