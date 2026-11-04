package com.firestar.dto;

import lombok.Data;

import java.util.List;

/**
 * 权限树节点 VO（管理端树形表格用）。
 * 注：sys_permission 无 sort_no 列，排序由 Service 层按 id 升序保证，
 * 调用方传入顺序即展示顺序（同 OrgTreeUtil 契约）。
 */
@Data
public class PermissionVO {

    private Long id;

    private Long parentId;

    private String permCode;

    private String permName;

    private String resourceType;

    private String actionType;

    private String status;

    /** 上级权限名称（Service 回填，平铺列表展示层级用） */
    private String parentName;

    private List<PermissionVO> children;
}
