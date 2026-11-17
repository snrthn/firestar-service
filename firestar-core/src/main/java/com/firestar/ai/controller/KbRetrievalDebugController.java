package com.firestar.ai.controller;

import com.firestar.ai.kb.KbChunkHit;
import com.firestar.ai.kb.KbEgressPolicy;
import com.firestar.ai.kb.KbRetriever;
import com.firestar.ai.kb.KbSearchContext;
import com.firestar.ai.vo.KbRetrieveHitVO;
import com.firestar.ai.vo.KbRetrieveVO;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.common.SecurityContextUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库检索可观测端点（P0-2）：让管理端能直接看到「一次检索到底召回了哪些片段」。
 *
 * <p>背景：线上检索通过 {@code search_knowledge} 工具隐性发生在 AI 对话里，SSE 帧不下发
 * tool_call，前端/运维拿不到召回明细，排查「答得不对」只能翻日志（而检索层原本连日志都没有）。
 * 本端点复用线上同一个 {@link KbRetriever} + {@link KbEgressPolicy} + 同一套 ACL 上下文构造，
 * 保证「调试看到的」=「模型实际拿到的」，只读、不改任何数据。</p>
 *
 * <p>权限：类级 {@code ai:kb:view}（与 {@link KbNodeController} 一致），ROLE_ADMIN 无条件放行。
 * 上下文构造逐字复用 ToolExecutor.search_knowledge 的范式：
 * {@code new KbSearchContext(isAdmin, currentAuthorities(), 0L)}，topK 上限与线上一致为 8。</p>
 */
@RestController
@RequestMapping("/ai/kb")
@RequirePerm("ai:kb:view")
public class KbRetrievalDebugController {

    private static final int DEFAULT_TOP_K = 4;
    private static final int MAX_TOP_K = 8;

    private final KbRetriever kbRetriever;
    private final KbEgressPolicy kbEgressPolicy;

    public KbRetrievalDebugController(KbRetriever kbRetriever, KbEgressPolicy kbEgressPolicy) {
        this.kbRetriever = kbRetriever;
        this.kbEgressPolicy = kbEgressPolicy;
    }

    /**
     * 只读检索：以当前登录用户身份检索知识库，返回命中片段/相关度/出处。
     *
     * @param query 查询词（必填）
     * @param topK  召回条数，<=0 取默认 4，上限 8（与线上 search_knowledge 一致）
     */
    @GetMapping("/retrieve")
    public Result<KbRetrieveVO> retrieve(@RequestParam String query,
                                         @RequestParam(required = false) Integer topK) {
        int k = (topK == null || topK <= 0) ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
        KbSearchContext ctx = new KbSearchContext(SecurityContextUtils.isAdmin(), currentAuthorities(), 0L);

        long start = System.currentTimeMillis();
        List<KbChunkHit> hits = kbRetriever.retrieve(query, ctx, k);
        List<KbChunkHit> allowed = hits.stream()
                .filter(h -> kbEgressPolicy.allow(h, ctx))
                .toList();
        long elapsed = System.currentTimeMillis() - start;

        KbRetrieveVO vo = new KbRetrieveVO();
        vo.setQuery(query);
        vo.setTopK(k);
        vo.setTotal(allowed.size());
        vo.setElapsedMs(elapsed);
        List<KbRetrieveHitVO> items = new ArrayList<>(allowed.size());
        for (KbChunkHit h : allowed) {
            KbRetrieveHitVO item = new KbRetrieveHitVO();
            item.setDocId(h.docId());
            item.setVersion(h.version());
            item.setSourceType(h.sourceType());
            item.setTitle(h.title());
            item.setHeadingPath(h.headingPath());
            item.setAnchor(h.anchor());
            item.setContent(h.content());
            item.setSourceRef(h.sourceRef());
            item.setScore(h.score());
            item.setFileId(h.fileId());
            items.add(item);
        }
        vo.setHits(items);
        return Result.success(vo);
    }

    /** 与 ToolExecutor.currentAuthorities() / KbDocService 逐字一致的权限收集口径。 */
    private Set<String> currentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (a != null && a.getAuthority() != null) {
                out.add(a.getAuthority());
            }
        }
        return out;
    }
}
