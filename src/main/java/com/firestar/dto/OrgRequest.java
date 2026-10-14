package com.firestar.dto;

import lombok.Data;

/**
 * 组织架构（部门）管理表单（新增 / 修改共用）。
 * 与 sys_org 业务列对齐；id 由路径参数传入，不走请求体。
 */
@Data
public class OrgRequest {

    /** 上级部门 ID（自引用，0 / null = 主部门） */
    private Long parentId;

    /** 部门名称 */
    private String orgName;

    /** 部门负责人 ID → sys_user.id（允许为空，表示暂未指定） */
    private Long leaderId;

    /** 排序（同级升序，小的靠前） */
    private Integer sortNo;

    /** 状态：ENABLED / DISABLED（字典 common_status） */
    private String status;
}
