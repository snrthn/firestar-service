package com.firestar.service;

import com.firestar.dto.MenuRequest;
import com.firestar.dto.MenuVO;

import java.util.List;

/**
 * 菜单管理服务（/system/menu）。
 * 与认证模块的 AuthService.menus（按角色过滤）不同，这里面向管理后台：
 * 返回全量菜单树（含隐藏 / 停用），并提供增删改与同级排序。
 */
public interface MenuService {

    /** 全量菜单树（管理页展示，不过滤 visible / status） */
    List<MenuVO> listTree();

    /** 新增菜单，返回新菜单 ID */
    Long create(MenuRequest req);

    /** 修改菜单 */
    void update(Long id, MenuRequest req);

    /** 删除菜单（存在子菜单时拒删） */
    void delete(Long id);

    /** 同级上移 / 下移（与相邻兄弟交换 sort_no）；direction: up / down */
    void move(Long id, String direction);
}
