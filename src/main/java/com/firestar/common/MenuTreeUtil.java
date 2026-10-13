package com.firestar.common;

import com.firestar.dto.MenuVO;
import com.firestar.entity.Menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单树构建工具。
 * 认证模块侧栏菜单（AuthServiceImpl.menus）与菜单管理页全量列表共用，
 * 单处维护父子挂接规则：parentId 为 0 / null / 不在集合内的视为根节点，
 * children 按 sortNo 升序（调用方传入顺序即展示顺序）。
 */
public final class MenuTreeUtil {

    private MenuTreeUtil() {
    }

    public static List<MenuVO> build(List<Menu> menus) {
        Map<Long, MenuVO> voMap = new LinkedHashMap<>();
        for (Menu m : menus) {
            MenuVO vo = new MenuVO();
            vo.setId(m.getId());
            vo.setParentId(m.getParentId());
            vo.setMenuName(m.getMenuName());
            vo.setRoutePath(m.getRoutePath());
            vo.setComponent(m.getComponent());
            vo.setPermCode(m.getPermCode());
            vo.setMenuType(m.getMenuType());
            vo.setSortNo(m.getSortNo());
            voMap.put(m.getId(), vo);
        }
        List<MenuVO> roots = new ArrayList<>();
        for (MenuVO vo : voMap.values()) {
            if (vo.getParentId() != null && vo.getParentId() != 0 && voMap.containsKey(vo.getParentId())) {
                MenuVO parent = voMap.get(vo.getParentId());
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(vo);
            } else {
                roots.add(vo);
            }
        }
        roots.sort(Comparator.comparingInt(v -> v.getSortNo() == null ? 0 : v.getSortNo()));
        return roots;
    }
}
