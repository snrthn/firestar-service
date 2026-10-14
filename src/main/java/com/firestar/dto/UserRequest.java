package com.firestar.dto;

import lombok.Data;

import java.util.List;

/**
 * 用户管理表单（创建 / 修改共用）。
 * 与 OrgRequest 的差别：username 创建后不可改（账号是登录标识，同 role_code 纪律），
 * password 仅创建时有效（修改走独立改密流程，V1 不在本模块提供重置）。
 */
@Data
public class UserRequest {

    /** 用户名/账号（创建后不可修改；格式：小写字母开头，可含小写字母、数字、下划线） */
    private String username;

    /** 姓名 */
    private String nickname;

    /** 密码（仅创建时必填，≥6 位；BCrypt 存储） */
    private String password;

    /** 所属部门 ID → sys_org.id（可空） */
    private Long deptId;

    /** 角色 ID 集合 → sys_role.id（可空；写操作整体替换） */
    private List<Long> roleIds;

    /** 状态（字典 common_status） */
    private String status;

    /** 岗位 ID → sys_post.id（可空，创建用户时非必填） */
    private Long postId;

    /** 手机号（可空） */
    private String phone;

    /** 邮箱（可空；格式由前端校验，后端不强制——与 phone 口径一致） */
    private String email;
}
