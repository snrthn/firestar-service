package com.firestar.ai.service;

import com.firestar.ai.entity.KbDoc;
import com.firestar.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    /** 根级目录 / 文档混排：10 DOC、20 FOLDER、30 DOC、40 FOLDER（sortNo 即视觉顺序） */
    private static List<KbDoc> mixedRoot() {
        List<KbDoc> out = new ArrayList<>();
        KbDoc a = doc(10, 0, 10, "/10/");
        a.setNodeType("DOC");
        out.add(a);
        out.add(doc(20, 0, 20, "/20/"));
        KbDoc c = doc(30, 0, 30, "/30/");
        c.setNodeType("DOC");
        out.add(c);
        out.add(doc(40, 0, 40, "/40/"));
        return out;
    }

    /** 模拟 Service 按 order 把 (index+1)*10 整组写回 sort_no（纯内存，不连库） */
    private static List<KbDoc> withOrder(List<KbDoc> all, long parentId, List<Long> order) {
        List<KbDoc> out = new ArrayList<>();
        for (KbDoc d : all) {
            KbDoc copy = doc(d.getId(), d.getParentId(), d.getSortNo(), d.getPath());
            copy.setNodeType(d.getNodeType());
            long p = d.getParentId() == null ? 0L : d.getParentId();
            int idx = order.indexOf(d.getId());
            if (idx >= 0 && p == parentId) {
                copy.setSortNo((idx + 1) * 10);
            }
            out.add(copy);
        }
        return out;
    }

    @Test
    @DisplayName("位置归一：空值=APPEND，小写容错，未知值原样留给解析器拒绝")
    void normalizePosition() {
        assertEquals("APPEND", KbTreeRules.normalizePosition(null));
        assertEquals("APPEND", KbTreeRules.normalizePosition("  "));
        assertEquals("BEFORE", KbTreeRules.normalizePosition(" before "));
        assertEquals("SIDEWAYS", KbTreeRules.normalizePosition("sideways"));
    }

    @Test
    @DisplayName("APPEND 追加到目标父末尾；目录与文档混排不按类型分组")
    void resolveAppendMixed() {
        assertEquals(List.of(10L, 20L, 40L, 30L),
                KbTreeRules.resolveSiblingOrder(mixedRoot(), 30, 0, "APPEND", null));
    }

    @Test
    @DisplayName("BEFORE / AFTER 以兄弟为锚点插入，而不是一律追加末尾（§21 Bug 根因）")
    void resolveBeforeAfter() {
        // 把 40(FOLDER) 拖到 20(FOLDER) 之前 → 40 必须出现在 20 前面
        assertEquals(List.of(10L, 40L, 20L, 30L),
                KbTreeRules.resolveSiblingOrder(mixedRoot(), 40, 0, "BEFORE", 20L));
        // 把 10(DOC) 拖到 30(DOC) 之后 → 10 必须紧跟 30
        assertEquals(List.of(20L, 30L, 10L, 40L),
                KbTreeRules.resolveSiblingOrder(mixedRoot(), 10, 0, "AFTER", 30L));
    }

    @Test
    @DisplayName("上移 / 下移：相邻兄弟互换位置，边界由前端禁用、这里顺序仍确定")
    void resolveStepMove() {
        // 30 上移 = BEFORE 前一个兄弟 20
        assertEquals(List.of(10L, 30L, 20L, 40L),
                KbTreeRules.resolveSiblingOrder(mixedRoot(), 30, 0, "BEFORE", 20L));
        // 10 下移 = AFTER 后一个兄弟 20
        assertEquals(List.of(20L, 10L, 30L, 40L),
                KbTreeRules.resolveSiblingOrder(mixedRoot(), 10, 0, "AFTER", 20L));
    }

    @Test
    @DisplayName("跨父级移动只重排目标父：原父留空隙不影响相对顺序")
    void resolveCrossParent() {
        // tree(): 1(根), 7(根), 3(挂在 1 下), 2(挂在 3 下)。把 3 移到根末尾
        assertEquals(List.of(1L, 7L, 3L),
                KbTreeRules.resolveSiblingOrder(tree(), 3, 0, "APPEND", null));
    }

    /**
     * §23：把节点「移动到另一个目录下」时，默认落位必须是目标目录的最后一位。
     * 这里模拟 Service.move 的完整链路（resolveSiblingOrder → 整组写回 sort_no →
     * 改 parentId → nodes() 的 parentId/sortNo/id 排序），不连库、不启 Spring。
     */
    @Test
    @DisplayName("移动到另一个目录下：APPEND 恒追加到目标子列表末尾，原父与目标父既有顺序均不变")
    void resolveAppendIntoAnotherFolder() {
        // 目录 1 下有 3(DOC)/4/5，目录 2 下有 6(DOC)/7/8；
        // 6、7 的 sortNo 相同（按 id 兜底），8 的间隔更大 —— 覆盖存量脏间隔与混排
        List<KbDoc> all = new ArrayList<>(List.of(
                doc(1, 0, 10, "/1/"), doc(2, 0, 20, "/2/"),
                doc(3, 1, 10, "/1/3/"), doc(4, 1, 20, "/1/4/"), doc(5, 1, 30, "/1/5/"),
                doc(6, 2, 10, "/2/6/"), doc(7, 2, 10, "/2/7/"), doc(8, 2, 20, "/2/8/")));
        all.get(2).setNodeType("DOC");
        all.get(5).setNodeType("DOC");

        // 把 3 从目录 1 移到目录 2（拖拽 inner / 「移动到」弹窗都发 APPEND + targetId=2）
        List<Long> order = KbTreeRules.resolveSiblingOrder(all, 3, 2, "APPEND", null);
        assertEquals(List.of(6L, 7L, 8L, 3L), order);

        // 模拟 Service：按 order 把 (index+1)*10 写回目标父的同级组，并改自身 parentId
        List<KbDoc> persisted = new ArrayList<>();
        for (KbDoc d : all) {
            long pid = d.getId() == 3L ? 2L : d.getParentId();
            KbDoc copy = doc(d.getId(), pid, d.getSortNo(), d.getPath());
            int idx = order.indexOf(d.getId());
            if (idx >= 0) {
                copy.setSortNo((idx + 1) * 10);
            }
            persisted.add(copy);
        }
        // 复刻 KbDocService.nodes() 的 Comparator
        persisted.sort((a, b) -> {
            long pa = a.getParentId() == null ? 0L : a.getParentId();
            long pb = b.getParentId() == null ? 0L : b.getParentId();
            if (pa != pb) return Long.compare(pa, pb);
            int sa = a.getSortNo() == null ? 0 : a.getSortNo();
            int sb = b.getSortNo() == null ? 0 : b.getSortNo();
            if (sa != sb) return Integer.compare(sa, sb);
            return Long.compare(a.getId(), b.getId());
        });

        List<Long> under2 = new ArrayList<>();
        List<Long> under1 = new ArrayList<>();
        int movedSortNo = -1;
        for (KbDoc d : persisted) {
            if (d.getParentId() == 2L) under2.add(d.getId());
            if (d.getParentId() == 1L) under1.add(d.getId());
            if (d.getId() == 3L) movedSortNo = d.getSortNo();
        }
        // 目标目录：6(10) 7(20) 8(30) 3(40) —— 新节点拿最大号，恒排最后
        assertEquals(List.of(6L, 7L, 8L, 3L), under2);
        assertEquals(40, movedSortNo);
        // 原目录：4、5 相对顺序与 sortNo 都不受影响（Service 只重排目标父）
        assertEquals(List.of(4L, 5L), under1);
    }

    @Test
    @DisplayName("连续多次拖拽顺序确定、不漂移（整组重排后再次插入）")
    void resolveRepeatedDragIsStable() {
        List<KbDoc> all = mixedRoot();
        List<Long> first = KbTreeRules.resolveSiblingOrder(all, 40, 0, "BEFORE", 10L);
        assertEquals(List.of(40L, 10L, 20L, 30L), first);
        all = withOrder(all, 0, first);
        List<Long> second = KbTreeRules.resolveSiblingOrder(all, 30, 0, "AFTER", 40L);
        assertEquals(List.of(40L, 30L, 10L, 20L), second);
    }

    @Test
    @DisplayName("同父 sortNo 相同时按 id 稳定排序，混排不出现随机顺序")
    void resolveTieBreakById() {
        List<KbDoc> tie = List.of(
                doc(6, 0, 10, "/6/"), doc(5, 0, 10, "/5/"), doc(9, 0, 10, "/9/"));
        assertEquals(List.of(5L, 6L, 9L, 7L),
                KbTreeRules.resolveSiblingOrder(tie, 7, 0, "APPEND", null));
    }

    @Test
    @DisplayName("非法锚点 / 锚点是自身 / 未知位置全部拒绝，不静默追加")
    void resolveRejectsBadAnchor() {
        assertThrows(BizException.class,
                () -> KbTreeRules.resolveSiblingOrder(mixedRoot(), 40, 0, "BEFORE", 999L));
        assertThrows(BizException.class,
                () -> KbTreeRules.resolveSiblingOrder(mixedRoot(), 30, 0, "BEFORE", 30L));
        assertThrows(BizException.class,
                () -> KbTreeRules.resolveSiblingOrder(mixedRoot(), 40, 0, "BEFORE", null));
        assertThrows(BizException.class,
                () -> KbTreeRules.resolveSiblingOrder(mixedRoot(), 40, 0, "SIDEWAYS", null));
        // 锚点在别的父级下，也不算同父兄弟
        List<KbDoc> cross = new ArrayList<>(mixedRoot());
        cross.add(doc(99, 20, 10, "/20/99/"));
        assertThrows(BizException.class,
                () -> KbTreeRules.resolveSiblingOrder(cross, 40, 0, "AFTER", 99L));
    }
}
