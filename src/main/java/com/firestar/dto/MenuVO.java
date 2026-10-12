package com.firestar.dto;

import lombok.Data;

import java.util.List;

/**
 * 菜单树节点（/auth/menus）。children 为下级节点，前端递归渲染。
 */
@Data
public class MenuVO {
    private Long id;
    private Long parentId;
    private String menuName;
    private String routePath;
    private String component;
    private String permCode;
    private String menuType;
    private Integer sortNo;
    private List<MenuVO> children;
}
