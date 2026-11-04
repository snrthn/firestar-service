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
    /** 外链跳转地址（LINK 型；前端侧栏渲染为 <a target=_blank>，不进 SPA 路由） */
    private String linkUrl;
    private String component;
    private String permCode;
    private String menuType;
    private Integer sortNo;
    /** 是否可见：0 隐藏 1 可见。仅决定侧栏展不展示（前端 visibleMenus 过滤），
     *  不参与访问控制——隐藏菜单 URL 仍可达（2026-10-15 语义修正） */
    private Integer visible;
    /** 状态：ENABLED / DISABLED。访问控制维度：停用菜单不出现在 /auth/menus
     *  可访问集，动态路由不注册，URL 直达落 403（禁用 ≈ 权限移除） */
    private String status;
    /** 是否缓存页面（keep-alive）：0 不缓存 1 缓存；仅 MENU 有意义。
     *  前端 AdminLayout 据此收集 cachedRouteNames 做 keep-alive include */
    private Integer isCache;
    /** 菜单图标：Element Plus 图标组件名（PascalCase），空则前端兜底默认图标 */
    private String icon;
    private List<MenuVO> children;
}
