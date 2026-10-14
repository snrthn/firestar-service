package com.firestar.dto;

import lombok.Data;

import java.util.List;

/**
 * 权限管理表单（创建 / 修改共用）。
 * permCode 创建后不可改（角色授权按 ID 关联，但编码是程序 @PreAuthorize 的引用标识，
 * 同 username / role_code / typeCode 纪律）。
 */
@Data
public class PermissionRequest {

    /** 权限编码（创建后不可修改；建议 resource:action 形式，如 user:manage） */
    private String permCode;

    /** 权限名称 */
    private String permName;

    /** 资源类型（字典 permission_resource，如 API / MENU / BUTTON） */
    private String resourceType;

    /** 操作类型（字典 permission_action，如 READ / WRITE / EXECUTE） */
    private String actionType;

    /** 父权限 ID（自引用，0/null = 根级） */
    private Long parentId;

    /** 状态（字典 common_status） */
    private String status;
}
