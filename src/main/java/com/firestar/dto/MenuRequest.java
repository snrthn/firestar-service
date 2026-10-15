package com.firestar.dto;

import lombok.Data;

/**
 * 菜单管理表单（新增 / 修改共用）。
 * 与 sys_menu 业务列对齐；id 由路径参数传入，不走请求体。
 */
@Data
public class MenuRequest {

    /** 父菜单 ID（自引用，0 / null = 顶级） */
    private Long parentId;

    /** 菜单名称 */
    private String menuName;

    /** 路由路径（CATALOG/BUTTON/LINK 允许为空） */
    private String routePath;

    /** 外链跳转地址（LINK 必填，须以 http(s):// 开头；其它类型忽略） */
    private String linkUrl;

    /** 前端组件路径（仅 MENU 使用） */
    private String component;

    /** 所需权限编码 → sys_permission.perm_code（BUTTON 必填） */
    private String permCode;

    /** 菜单类型：CATALOG 目录 / MENU 菜单 / BUTTON 按钮 / LINK 外链 */
    private String menuType;

    /** 排序（同级升序，小的靠前） */
    private Integer sortNo;

    /** 是否可见：0 隐藏 1 可见 */
    private Integer visible;

    /** 是否缓存页面（keep-alive）：0 不缓存 1 缓存；仅 MENU 型有意义 */
    private Integer isCache;

    /** 状态：ENABLED / DISABLED */
    private String status;

    /** 菜单图标：Element Plus 图标组件名（PascalCase）；可空，空则用默认图标兜底 */
    private String icon;
}
