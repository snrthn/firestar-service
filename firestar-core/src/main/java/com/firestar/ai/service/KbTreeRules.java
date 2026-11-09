package com.firestar.ai.service;

import com.firestar.ai.entity.KbDoc;
import com.firestar.exception.BizException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库树形 / 正文的<b>纯规则层</b>：无 Spring、无 Mapper、无 HTTP 上下文，可直接单测。
 *
 * <p>规则与前端 {@code utils/kbTree.ts}、{@code utils/markdown.ts} <b>逐条对齐</b>：
 * 前端在拖拽、保存前做的是同一份判定，后端不能另起一套口径，否则会出现
 * 「前端让拖、后端拒绝」或「前端拦了、后端放行」的割裂。</p>
 *
 * <p>MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS、CHECK 也不生效（V208 注释），
 * 因此防环、路径合法性、MD 规范这些约束全部落在这里由 Service 调用。</p>
 */
public final class KbTreeRules {

    /** 节点数超过该量级，后端置 lazyRequired，前端 el-tree 切按父节点懒加载 */
    public static final int KB_LAZY_THRESHOLD = 2000;
    /** 按父节点分页的单页上限（对齐飞书 wiki node list 的 page_size 上限） */
    public static final int KB_MAX_PAGE_SIZE = 50;

    // 与 markdown.ts 的 FENCE_RE / 标题规则 / IMAGE_RE 保持一致
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+");
    private static final Pattern FILE_REF = Pattern.compile("\\]\\(file:(\\d+)\\)");

    private KbTreeRules() {
    }

    /** 物化路径 /1/3/7/；父节点 path 缺失时按根处理（对齐 computeKbPath） */
    public static String computePath(String parentPath, long id) {
        String base = parentPath == null || parentPath.isBlank() ? "/" : parentPath;
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base + id + "/";
    }

    /** 同级排序号 = 当前最大值 + 10（留插入余量，对齐 nextSortNo） */
    public static int nextSortNo(List<KbDoc> all, long parentId) {
        int max = 0;
        for (KbDoc d : all) {
            if (d.getParentId() != null && d.getParentId() == parentId
                    && d.getSortNo() != null && d.getSortNo() > max) {
                max = d.getSortNo();
            }
        }
        return max + 10;
    }

    /**
     * 防环校验：把 id 挂到 newParentId 下是否合法。
     * 拒绝：移到自身、目标父节点不存在、移进自己的后代子树、祖先链本身有脏环。
     */
    public static void assertNoCycle(List<KbDoc> all, long id, long newParentId) {
        if (newParentId == 0) {
            return;
        }
        if (newParentId == id) {
            throw new BizException("父节点不能是节点自身");
        }
        Map<Long, KbDoc> byId = new HashMap<>();
        for (KbDoc d : all) {
            if (d.getId() != null) {
                byId.put(d.getId(), d);
            }
        }
        KbDoc target = byId.get(newParentId);
        if (target == null) {
            throw new BizException("目标父节点不存在: " + newParentId);
        }
        Set<Long> visited = new HashSet<>();
        KbDoc cursor = target;
        while (cursor != null && cursor.getId() != null && visited.add(cursor.getId())) {
            if (cursor.getId() == id) {
                throw new BizException("不能把节点移动到自己的子目录下，会造成死循环");
            }
            Long pid = cursor.getParentId();
            cursor = (pid == null || pid == 0) ? null : byId.get(pid);
        }
    }

    /** 取所有后代（不含自身），按广度优先顺序；visited 兜住历史脏环，避免死循环 */
    public static List<KbDoc> descendants(List<KbDoc> all, long id) {
        Map<Long, List<KbDoc>> childrenByParent = new HashMap<>();
        for (KbDoc d : all) {
            Long pid = d.getParentId();
            if (pid != null) {
                childrenByParent.computeIfAbsent(pid, k -> new ArrayList<>()).add(d);
            }
        }
        List<KbDoc> out = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(id);
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(id);
        while (!queue.isEmpty()) {
            Long parent = queue.poll();
            for (KbDoc child : childrenByParent.getOrDefault(parent, List.of())) {
                if (child.getId() != null && visited.add(child.getId())) {
                    out.add(child);
                    queue.add(child.getId());
                }
            }
        }
        return out;
    }

    /** 移动后重算后代 path：把 oldBase 前缀换成 newBase（两者都以 '/' 结尾） */
    public static String rebasePath(String path, String oldBase, String newBase) {
        if (path == null || oldBase == null || newBase == null) {
            return path;
        }
        if (path.startsWith(oldBase)) {
            return newBase + path.substring(oldBase.length());
        }
        return path;
    }

    /**
     * ACL 可见性：null/空 = 全员可见；ROLE_ADMIN 无条件放行；
     * 否则当前用户 authorities（role_code 直灌 / PERM:xxx）命中 acl_roles 任一标签即可见。
     */
    public static boolean isVisible(String aclRoles, boolean admin, Collection<String> authorities) {
        if (admin || aclRoles == null || aclRoles.isBlank()) {
            return true;
        }
        Set<String> owned = new HashSet<>();
        if (authorities != null) {
            for (String a : authorities) {
                if (a == null) {
                    continue;
                }
                owned.add(a);
                if (a.startsWith("PERM:")) {
                    owned.add(a.substring(5));
                }
            }
        }
        for (String raw : aclRoles.split(",")) {
            String role = raw.trim();
            if (!role.isEmpty() && owned.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * MD 规范校验（与前端 validateMarkdown 同口径）：唯一 H1、标题层级不跳级、
     * fenced code block 内的 # 不当标题。返回可读问题列表，空列表 = 通过。
     */
    public static List<String> validateMarkdown(String md) {
        List<String> issues = new ArrayList<>();
        String[] lines = (md == null ? "" : md).split("\n", -1);
        int h1 = 0;
        int prevLevel = 0;
        boolean inFence = false;
        char fenceChar = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher fence = FENCE.matcher(lines[i]);
            if (fence.find()) {
                char c = fence.group(1).charAt(0);
                if (!inFence) {
                    inFence = true;
                    fenceChar = c;
                } else if (c == fenceChar) {
                    inFence = false;
                    fenceChar = 0;
                }
                continue;
            }
            if (inFence) {
                continue;
            }
            Matcher h = HEADING.matcher(lines[i]);
            if (!h.find()) {
                continue;
            }
            int level = h.group(1).length();
            if (level == 1) {
                h1++;
            }
            if (prevLevel > 0 && level > prevLevel + 1) {
                issues.add("第 " + (i + 1) + " 行：标题从 H" + prevLevel
                        + " 跳到 H" + level + "，请补齐中间层级");
            }
            prevLevel = level;
        }
        if (h1 == 0) {
            issues.add("缺少唯一的 H1 标题（# 文档标题）");
        } else if (h1 > 1) {
            issues.add("存在 " + h1 + " 个 H1 标题，知识库要求唯一 H1");
        }
        return issues;
    }

    /** 正文里的 file:&lt;id&gt; 引用（去重、保序）；与前端 collectFileRefs 同一正则 */
    public static Set<Long> collectFileRefs(String md) {
        Set<Long> ids = new LinkedHashSet<>();
        Matcher m = FILE_REF.matcher(md == null ? "" : md);
        while (m.find()) {
            try {
                ids.add(Long.parseLong(m.group(1)));
            } catch (NumberFormatException ignored) {
                // 超长数字不是合法 fileId，跳过而不是让保存 500
            }
        }
        return ids;
    }

    /** pageToken 本实现为十进制 offset（前端原样回传，格式不对外承诺） */
    public static int parseOffset(String pageToken) {
        if (pageToken == null || pageToken.isBlank()) {
            return 0;
        }
        try {
            int v = Integer.parseInt(pageToken.trim());
            if (v < 0) {
                throw new BizException("pageToken 非法");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new BizException("pageToken 非法");
        }
    }

    public static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return KB_MAX_PAGE_SIZE;
        }
        return Math.min(pageSize, KB_MAX_PAGE_SIZE);
    }

    public static <T> List<T> slice(List<T> all, int offset, int limit) {
        if (offset >= all.size()) {
            return List.of();
        }
        return new ArrayList<>(all.subList(offset, Math.min(all.size(), offset + limit)));
    }
}
