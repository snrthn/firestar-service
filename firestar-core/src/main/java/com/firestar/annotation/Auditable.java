package com.firestar.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解（AOP 自动落库 sys_audit_log）。
 *
 * <p>设计目标（虎哥 2026-10-15 定稿）：
 * <ul>
 *   <li><b>只记关键字段</b>：{@link #fields()} 显式声明要快照的字段名，不做全量 JSON 镜像；</li>
 *   <li><b>可扩展</b>：任何业务方法加一个注解即可接入，无需改切面、无需改表；
 *       未来业务模块（财务/审批等）直接复用同一套机制；</li>
 *   <li><b>事务安全</b>：切面在业务方法返回成功后、事务提交后异步落库，
 *       业务回滚则不会写审计（避免"操作失败却留下审计"的脏数据）；</li>
 *   <li><b>before/after 关键字段 diff</b>：UPDATE/DELETE 通过 {@link #entityClass()} 加载旧值，
 *       与 {@link #fields()} 中声明的新值做对比，谁改了什么一目了然。</li>
 * </ul>
 *
 * <p>落库时机与线程：切面在同步阶段只抓取「操作人 / IP / 目标 id / before·after 快照」，
 * 真正 insert 交给独立线程池（logWriteExecutor）执行，切面所在事务提交后再落库。
 *
 * <p>典型用法（写在 Service 实现方法上，包住 @Transactional）：
 * <pre>{@code
 *   @Auditable(operationType = "CREATE", targetType = "USER",
 *              targetIdSpEl = "#result", entityClass = User.class,
 *              fields = {"username", "nickname", "status", "deptId"})
 *   public Long createUser(UserRequest req) { ... }
 *
 *   @Auditable(operationType = "UPDATE", targetType = "USER",
 *              targetIdSpEl = "#id", entityClass = User.class,
 *              fields = {"username", "nickname", "status", "deptId"})
 *   public void updateUser(Long id, UserRequest req) { ... }
 *
 *   @Auditable(operationType = "DELETE", targetType = "USER",
 *              targetIdSpEl = "#id", entityClass = User.class,
 *              fields = {"username", "status"})
 *   public void deleteUser(Long id) { ... }
 * }</pre>
 *
 * <p>约定：
 * <ul>
 *   <li>{@code targetIdSpEl} 为空 → 不记 targetId（极少用）；</li>
 *   <li>after 快照对象：{@code #result} 是复杂对象（实体/VO/DTO）则用之；否则取方法第一个复杂参数（通常是 req）；</li>
 *   <li>before 快照：仅 UPDATE/DELETE 且 entityClass 非空时加载旧实体；CREATE 不加载。</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** 操作类型（如 CREATE / UPDATE / DELETE / UPLOAD / DOWNLOAD，字典 audit_operation 取值） */
    String operationType();

    /** 目标对象类型（如 USER / ROLE / MENU / DICT_TYPE / DICT_DATA / FILE） */
    String targetType();

    /**
     * 目标对象 ID 的 SpEL 表达式（基于方法参数求值，变量名即参数名）。
     * 常见取值：
     *   - "#result"      → 方法返回值即 id（如 createXxx 返回 Long id）；
     *   - "#result.id"   → 方法返回值（VO/实体）的 id 字段（如 upload 返回 FileVO）；
     *   - "#id"          → 名为 id 的路径参数（updateXxx / deleteXxx 的 @PathVariable Long id）。
     */
    String targetIdSpEl() default "";

    /** before 快照用：目标实体类（UPDATE/DELETE 时按 id 加载旧值）。CREATE 或非实体类审计留 Void.class */
    Class<?> entityClass() default Void.class;

    /** 要快照的关键字段名（同时作用于 before/after 对象；对象没有的字段自动跳过） */
    String[] fields() default {};

    /** 备注（可选，留空则切面自动生成「{operationType} {targetType}」） */
    String remark() default "";
}
