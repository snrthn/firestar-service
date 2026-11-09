package com.firestar.ai.service;

import com.firestar.ai.entity.KbDoc;
import com.firestar.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库纯规则层单测（不连库、不启 Spring）。
 *
 * <p>口径与前端 kbTree.test.ts / markdown.test.ts 对齐，覆盖设计文档 §13 与 §16：
 * 防环、物化路径、子树重算、ACL 可见性、MD 规范、fileRefs 提取、分页游标。</p>
 */
class KbTreeRulesTest {

    private static KbDoc doc(long id, long parentId, int sortNo, String path) {
        KbDoc d = new KbDoc();
        d.setId(id);
        d.setParentId(parentId);
        d.setSortNo(sortNo);
        d.setPath(path);
        d.setTitle("节点" + id);
        d.setNodeType("FOLDER");
        return d;
    }

    /** 1 → 3 → 2 的三层树，另加一个根 7 */
    private static List<KbDoc> tree() {
        return List.of(
                doc(1, 0, 10, "/1/"),
                doc(3, 1, 10, "/1/3/"),
                doc(2, 3, 10, "/1/3/2/"),
                doc(7, 0, 20, "/7/"));
    }

    @Test
    @DisplayName("物化路径：根 / 嵌套 / 父 path 缺斜杠都归一")
    void computePath() {
        assertEquals("/12/", KbTreeRules.computePath(null, 12));
        assertEquals("/1/3/9/", KbTreeRules.computePath("/1/3/", 9));
        assertEquals("/1/3/9/", KbTreeRules.computePath("/1/3", 9));
        assertEquals("/9/", KbTreeRules.computePath("  ", 9));
    }

    @Test
    @DisplayName("排序号取同级最大 +10，空组从 10 起")
    void nextSortNo() {
        assertEquals(30, KbTreeRules.nextSortNo(tree(), 0));
        assertEquals(20, KbTreeRules.nextSortNo(tree(), 3));
        assertEquals(20, KbTreeRules.nextSortNo(tree(), 1));
        assertEquals(10, KbTreeRules.nextSortNo(tree(), 999));
    }

    @Test
    @DisplayName("防环：根挂载放行；移到自身 / 自己的后代 / 不存在的父节点全拒")
    void noCycle() {
        List<KbDoc> all = tree();
        KbTreeRules.assertNoCycle(all, 3, 0);
        KbTreeRules.assertNoCycle(all, 3, 7);
        assertThrows(BizException.class, () -> KbTreeRules.assertNoCycle(all, 3, 3));
        assertThrows(BizException.class, () -> KbTreeRules.assertNoCycle(all, 1, 2),
                "把根 1 挂到自己的后代 2 下必须拒绝");
        assertThrows(BizException.class, () -> KbTreeRules.assertNoCycle(all, 3, 999));
    }

    @Test
    @DisplayName("历史脏环不会让防环校验死循环")
    void dirtyCycleTerminates() {
        List<KbDoc> cyclic = List.of(doc(1, 2, 10, "/2/1/"), doc(2, 1, 10, "/1/2/"));
        // 目标 2 的祖先链 2→1→2→1… 被 visited 截断，且不含 id=9，判定可挂
        KbTreeRules.assertNoCycle(cyclic, 9, 2);
    }

    @Test
    @DisplayName("descendants 只取后代且按广度优先；脏环不死循环")
    void descendants() {
        List<Long> ids = KbTreeRules.descendants(tree(), 1).stream().map(KbDoc::getId).toList();
        assertEquals(List.of(3L, 2L), ids);
        assertEquals(List.of(), KbTreeRules.descendants(tree(), 2));
    }

    @Test
    @DisplayName("移动后子树 path 用 newBase 重算")
    void rebasePath() {
        assertEquals("/7/3/", KbTreeRules.rebasePath("/1/3/", "/1/3/", "/7/3/"));
        assertEquals("/7/3/2/", KbTreeRules.rebasePath("/1/3/2/", "/1/3/", "/7/3/"));
        assertEquals("/1/3/2/", KbTreeRules.rebasePath("/1/3/2/", "/no-match/", "/7/"));
    }

    @Test
    @DisplayName("ACL：空=全员可见、ADMIN 放行、命中角色可见、未命中不可见、PERM: 前缀归一")
    void acl() {
        assertTrue(KbTreeRules.isVisible(null, false, Set.of()));
        assertTrue(KbTreeRules.isVisible("  ", false, Set.of()));
        assertTrue(KbTreeRules.isVisible("FINANCE", false, Set.of("PERM:FINANCE")));
        assertTrue(KbTreeRules.isVisible("FINANCE, HR", false, Set.of("HR")));
        assertTrue(KbTreeRules.isVisible("FINANCE", true, Set.of()));
        assertFalse(KbTreeRules.isVisible("FINANCE, HR", false, Set.of("ROLE_USER")));
        assertFalse(KbTreeRules.isVisible("FINANCE", false, Set.of("ROLE_USER")));
        assertFalse(KbTreeRules.isVisible("FINANCE", false, Set.of("ROLE_USER")));
        assertFalse(KbTreeRules.isVisible("FINANCE", false, null));
    }

    @Test
    @DisplayName("MD 规范：合规文档零问题")
    void markdownOk() {
        String md = "# 差旅费报销制度\n\n## 适用范围\n\n正文\n\n### 时限\n\n15 个工作日";
        assertEquals(List.of(), KbTreeRules.validateMarkdown(md));
    }

    @Test
    @DisplayName("MD 规范：缺 H1 / 多 H1 / 标题跳级全部拦下")
    void markdownIssues() {
        assertTrue(KbTreeRules.validateMarkdown("## 没有 H1").stream()
                .anyMatch(s -> s.contains("缺少唯一")));
        assertTrue(KbTreeRules.validateMarkdown("# A\n# B").stream()
                .anyMatch(s -> s.contains("2 个 H1")));
        assertTrue(KbTreeRules.validateMarkdown("# A\n### 跳级\n").stream()
                .anyMatch(s -> s.contains("跳到 H3")));
    }

    @Test
    @DisplayName("MD 规范：fenced code 里的 # 不算标题（与前端 FENCE_RE 对齐）")
    void markdownFence() {
        String md = "# 标题\n\n```bash\n# 这是注释，不是 H1\n#### 也不是跳级\n```\n\n## 正常章节";
        assertEquals(List.of(), KbTreeRules.validateMarkdown(md));
    }

    @Test
    @DisplayName("collectFileRefs：去重保序，忽略外链与非法协议")
    void fileRefs() {
        String md = "![a](file:12)\n\n![b](file:12)\n\n[c](file:34)\n"
                + "![x](https://example.com/a.png)\n![y](file:abc)";
        assertEquals(Set.of(12L, 34L), KbTreeRules.collectFileRefs(md));
        assertEquals(List.of(12L, 34L), List.copyOf(KbTreeRules.collectFileRefs(md)));
        assertTrue(KbTreeRules.collectFileRefs(null).isEmpty());
    }

    @Test
    @DisplayName("分页游标：空 token=0、非法 token 抛业务异常、pageSize 上限 50")
    void paging() {
        assertEquals(0, KbTreeRules.parseOffset(null));
        assertEquals(12, KbTreeRules.parseOffset(" 12 "));
        assertThrows(BizException.class, () -> KbTreeRules.parseOffset("abc"));
        assertThrows(BizException.class, () -> KbTreeRules.parseOffset("-1"));
        assertEquals(50, KbTreeRules.normalizePageSize(null));
        assertEquals(50, KbTreeRules.normalizePageSize(0));
        assertEquals(50, KbTreeRules.normalizePageSize(999));
        assertEquals(10, KbTreeRules.normalizePageSize(10));
        assertEquals(List.of(3, 4), KbTreeRules.slice(List.of(1, 2, 3, 4, 5), 2, 2));
        assertEquals(List.of(), KbTreeRules.slice(List.of(1, 2), 9, 2));
    }
}
