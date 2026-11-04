package com.firestar.dto;

import lombok.Data;

/**
 * 角色管理表单（创建 / 修改共用）。
 * roleCode 创建后不可改（程序按编码引用角色，同 username / typeCode 纪律）。
 * 权限分配不走本表单，走独立的 PUT /{id}/perms（RolePermRequest）。
 */
@Data
public class RoleRequest {

    /** 角色编码（创建后不可修改；字母开头，可含字母、数字、下划线） */
    private String roleCode;

    /** 角色名称 */
    private String roleName;

    /** 排序（同级内数字越小越靠前） */
    private Integer sortNo;

    /** 状态（字典 common_status） */
    private String status;

    /** 备注 */
    private String remark;
}
