package com.firestar.seed;

import com.firestar.entity.Menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 种子（菜单）完整性静态校验。
 *
 * <p>只做纯逻辑判断，不碰数据库，便于单测。两条规则对应《开发规范》第十一~十三节：
 * <ol>
 *   <li>菜单 {@code route_path} 必须以 {@code /} 开头，否则 Vue Router 会从 {@code /system/*} 页面
 *       把路径当作「相对当前路由」解析成 {@code /system/system/xxx}，命中 catch-all → 403。</li>
 *   <li>带 {@code perm_code} 的 {@code MENU} 节点必须有子 {@code BUTTON} 节点。对应 RBAC 双线模型：
 *       前端的 {@code v-has-perm} 只认 {@code menu_type='BUTTON'} 的菜单节点，
 *       漏建 BUTTON 节点 → 「页面在、按钮没」。</li>
 * </ol>
 */
public final class SeedIntegrityCheck {

    private SeedIntegrityCheck() {
    }

    public static List<String> check(List<Menu> menus) {
        List<String> violations = new ArrayList<>();
        if (menus == null) {
            return violations;
        }
        for (Menu m : menus) {
            String type = m.getMenuType();
            String path = m.getRoutePath();

            // 规则1：有 route_path 的节点必须以 / 开头
            if (path != null && !path.startsWith("/")) {
                violations.add(String.format(
                        "菜单 route_path 缺少前导 '/'：id=%s, menuName=%s, routePath='%s'"
                                + "（会导致从 /system/* 页相对解析成 403，参考 V108）",
                        m.getId(), m.getMenuName(), path));
            }

            // 规则2：MENU 节点带 perm_code 但无子 BUTTON 节点
            if ("MENU".equals(type) && m.getPermCode() != null && !m.getPermCode().isBlank()) {
                boolean hasButtonChild = menus.stream().anyMatch(c ->
                        "BUTTON".equals(c.getMenuType()) && Objects.equals(c.getParentId(), m.getId()));
                if (!hasButtonChild) {
                    violations.add(String.format(
                            "MENU 节点带 perm_code 但缺少子 BUTTON 节点：id=%s, menuName=%s, permCode='%s'"
                                    + "（前端 v-has-perm 找不到 BUTTON 节点 → 按钮不显示，参考 V102/V106/V107）",
                            m.getId(), m.getMenuName(), m.getPermCode()));
                }
            }
        }
        return violations;
    }
}
