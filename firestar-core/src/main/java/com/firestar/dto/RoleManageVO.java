package com.firestar.dto;

import lombok.Data;

/**
 * 角色管理列表 VO（管理端分页用）。
 * 与 profile 接口的 RoleVO 分开：那边只要 roleCode/roleName 两个字段，
 * 这边要排序/状态/备注；复用会往登录响应里塞 null 字段。
 */
@Data
public class RoleManageVO {

    private Long id;

    private String roleCode;

    private String roleName;

    private Integer sortNo;

    private String status;

    private String remark;
}
