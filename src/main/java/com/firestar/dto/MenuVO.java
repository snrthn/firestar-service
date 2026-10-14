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
    /** 是否可见：0 隐藏 1 可见（管理页展示用；侧栏渲染忽略） */
    private Integer visible;
    /** 状态：ENABLED / DISABLED（管理页展示用；侧栏渲染忽略） */
    private String status;
    /** 菜单图标：Element Plus 图标组件名（PascalCase），空则前端兜底默认图标 */
    private String icon;
    private List<MenuVO> children;
}
