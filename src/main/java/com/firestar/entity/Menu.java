package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_menu")
public class Menu extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 菜单名称 */
    private String menuName;

    /** 父菜单 ID（自引用，0=根） */
    private Long parentId;

    /** 路由路径 */
    private String routePath;

    /** 外链跳转地址（menu_type=LINK 时必填，须以 http(s):// 开头；其它类型为空） */
    private String linkUrl;

    /** 前端组件 */
    private String component;

    /** 所需权限编码 → sys_permission.perm_code */
    private String permCode;

    /** 菜单类型（字典 menu_type）：CATALOG 目录 / MENU 菜单 / BUTTON 按钮 / LINK 外链 */
    private String menuType;

    /** 排序 */
    private Integer sortNo;

    /** 是否可见：0隐藏 1可见 */
    private Integer visible;

    /** 是否缓存页面（keep-alive）：0 不缓存 1 缓存；仅 MENU 型有意义 */
    private Integer isCache;

    /** 状态（字典 common_status） */
    private String status;

    /** 菜单图标：Element Plus 图标组件名（PascalCase，如 User/Setting）；空则用默认图标兜底 */
    private String icon;
}
