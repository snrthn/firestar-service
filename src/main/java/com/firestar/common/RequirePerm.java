package com.firestar.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明某个 Controller 类 / 方法所需的权限点（sys_permission.perm_code）。
 *
 * 配合 {@code PermissionInterceptor} 做后端 API 级权限拦截：
 *   - 标注在类上 → 该 Controller 下所有接口都需要其中任一权限（模块级粗粒度，
 *     与本项目「一个 perm_code 管整个模块」的约定一致，如 rbac:user）；
 *   - 标注在方法上 → 覆盖类级注解，仅该方法需要；
 *   - 均未标注 → 视为公开接口（只需 JWT，不校验具体权限）。
 *
 * 取值为 perm_code 列表，ANY 语义（拥有其一即通过）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {
    String[] value();
}
