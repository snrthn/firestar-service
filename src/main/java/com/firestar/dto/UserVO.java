package com.firestar.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户管理列表 VO。deptName / roleNames 由 Service 批量回填（避免 N+1），
 * roleIds 供编辑弹窗回显已选角色。
 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String nickname;

    private Long deptId;

    private String deptName;

    private List<Long> roleIds;

    private List<String> roleNames;

    private String status;

    /** 是否首次登录强制改密（0否 1是） */
    private Integer forceChangePassword;

    private LocalDateTime lastLoginAt;

    /** 岗位 ID → sys_post.id（单岗，非必填） */
    private Long postId;

    /** 岗位名称（Service 回填） */
    private String postName;

    /** 手机号 */
    private String phone;
}
