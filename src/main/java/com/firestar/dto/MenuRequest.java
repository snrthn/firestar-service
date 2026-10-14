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

    /** 路由路径（CATALOG 允许为空） */
    private String routePath;

    /** 前端组件路径（CATALOG 允许为空） */
    private String component;

    /** 所需权限编码 → sys_permission.perm_code */
    private String permCode;

    /** 菜单类型：CATALOG 目录 / MENU 菜单 */
    private String menuType;

    /** 排序（同级升序，小的靠前） */
    private Integer sortNo;

    /** 是否可见：0 隐藏 1 可见 */
    private Integer visible;

    /** 状态：ENABLED / DISABLED */
    private String status;

    /** 菜单图标：Element Plus 图标组件名（PascalCase）；可空，空则用默认图标兜底 */
    private String icon;
}
