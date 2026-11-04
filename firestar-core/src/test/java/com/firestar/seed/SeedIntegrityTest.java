package com.firestar.seed;

import com.firestar.entity.Menu;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子完整性校验的纯逻辑单测（不依赖数据库，可在本地/CI 离线跑）。
 *
 * <p>覆盖两种真实翻车模式：① 菜单 route_path 漏写前导 {@code /}（V108 修复的那个）；
 * ② 带 perm_code 的 MENU 节点没有子 BUTTON 节点（V102/V106/V107 那一类）。
 * 若有人改坏 {@link SeedIntegrityCheck} 的逻辑，本测试会失败。</p>
 */
class SeedIntegrityTest {

    private Menu menu(Long id, Long parentId, String type, String routePath, String permCode, String name) {
        Menu m = new Menu();
        m.setId(id);
        m.setParentId(parentId);
        m.setMenuType(type);
        m.setRoutePath(routePath);
        m.setPermCode(permCode);
        m.setMenuName(name);
        return m;
    }

    @Test
    void cleanSeed_hasNoViolations() {
        List<Menu> clean = Arrays.asList(
                menu(1L, 0L, "MENU", "/system/x", "x:list", "X管理"),
                menu(2L, 1L, "BUTTON", null, "x:add", "X-新增"),
                menu(3L, 0L, "CATALOG", null, null, "目录")
        );
        assertTrue(SeedIntegrityCheck.check(clean).isEmpty(), "干净种子不应有违规项");
    }

    @Test
    void routePathMissingLeadingSlash_isFlagged() {
        // 隔离规则1：只给 route_path（permCode 置空，避免同时命中规则2）
        List<Menu> bad = Arrays.asList(
                menu(1L, 0L, "MENU", "system/y", null, "Y管理")  // 缺前导 /
        );
        List<String> v = SeedIntegrityCheck.check(bad);
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("route_path"), v.get(0));
    }

    @Test
    void menuWithPermCodeButNoButtonChild_isFlagged() {
        List<Menu> bad = Arrays.asList(
                menu(1L, 0L, "MENU", "/system/z", "z:list", "Z管理")  // 有 perm_code 无 BUTTON 子
        );
        List<String> v = SeedIntegrityCheck.check(bad);
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("BUTTON"), v.get(0));
    }

    @Test
    void buttonChildSatisfiesPermCodeRule() {
        List<Menu> ok = Arrays.asList(
                menu(1L, 0L, "MENU", "/system/z", "z:list", "Z管理"),
                menu(2L, 1L, "BUTTON", null, "z:add", "Z-新增")
        );
        assertTrue(SeedIntegrityCheck.check(ok).isEmpty(), "有 BUTTON 子节点即合规");
    }
}
