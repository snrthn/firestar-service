package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 登录日志（sys_login_log）。只增不删不改，故不带逻辑删除三字段。
 *
 * <p>与 {@link AuditLog} 的区别：审计日志永久保留且不可改（红线 5）；
 * 登录日志体量大、取证价值随时间衰减，可定期清理（建议保留 1 年）。
 *
 * <p>继承 {@link AuditedEntity}：复用 id / tenantId / version / ext / created_by / created_at /
 * updated_by / updated_at（created_at / updated_at 由 AuditMetaObjectHandler 自动填充）。
 * 业务列仅登录相关字段。
 */
@Getter
@Setter
@TableName(value = "sys_login_log")
public class LoginLog extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 用户 ID（登录失败时可能为空） */
    private Long userId;

    /** 登录账号（冗余，用户被删后仍可追溯） */
    private String username;

    /** 结果（字典 login_status）：SUCCESS / FAIL / KICKED_OUT */
    private String loginStatus;

    /** 登录 IP */
    private String ipAddress;

    /** 浏览器 UA */
    private String userAgent;

    /** 失败原因（密码错误 / 账号禁用 / 被顶下线等） */
    private String failReason;

    /** 登录时间 */
    private LocalDateTime loginAt;
}
