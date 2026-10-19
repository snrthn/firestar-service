package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_user")
public class User extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 用户名/账号 */
    private String username;

    /** 姓名 */
    private String nickname;

    /** 密码哈希（BCrypt） */
    private String password;

    /** 是否强制改密：0否 1是 */
    private Integer forceChangePassword;

    /** 所属部门 ID → sys_org.id */
    private Long deptId;

    /** 直属上级 ID → sys_user.id */
    private Long parentId;

    /** 状态（字典 common_status） */
    private String status;

    /** 最后登录时间 */
    private LocalDateTime lastLoginAt;

    /** 最后登录 IP */
    private String lastLoginIp;

    /** 当前有效会话 JTI（单点登录：登录即轮换，旧 token 因不匹配即时失效） */
    private String currentJti;

    /** 手机号 */
    private String phone;

    /** 邮箱（V14 新增，可空，不唯一） */
    private String email;

    /** 头像文件ID（sys_file_storage.id），为空则昵称首字兜底（V28 新增） */
    private String avatarUrl;

    /** 岗位 ID → sys_post.id（单岗，非必填） */
    private Long postId;
}
