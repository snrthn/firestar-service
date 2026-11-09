package com.firestar.ai.controller;

import com.firestar.ai.service.KbDocService;
import com.firestar.ai.vo.KbDocDetailVO;
import com.firestar.ai.vo.KbDocUpdateReq;
import com.firestar.ai.vo.KbNodeCreateReq;
import com.firestar.ai.vo.KbNodeMoveReq;
import com.firestar.ai.vo.KbNodePageVO;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.common.SecurityContextUtils;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库节点 / 文档接口（步骤 4.5，契约见设计文档 §13.4 + §16）。
 *
 * <p>路径沿用 AI 中心前缀（/ai/prompt-config、/ai/model-config），前端 api/kb.ts 已按此写死。
 * 权限三段式：类级 ai:kb:view（GET），写操作 ai:kb:edit，发布 ai:kb:publish —— 注意
 * {@code PermissionInterceptor} 的<b>方法级注解覆盖类级</b>，写方法必须逐个显式标注。</p>
 *
 * <p>共 8 个端点，比 §13.4 契约表多一个 {@code PUT /nodes/{id}}：
 * 上一轮做了树内 inline 重命名，但契约表里漏了对应写接口，本轮补齐（见 §16）。</p>
 */
@RestController
@RequestMapping("/ai/kb")
@RequirePerm("ai:kb:view")
public class KbNodeController {

    private final KbDocService service;

    public KbNodeController(KbDocService service) {
        this.service = service;
    }

    /**
     * 节点骨架（不含正文）。不传 parentId = 一次返回全量可见骨架；
     * 传值 = 只取直接子节点；pageToken 为后端下发的不透明游标，前端原样回传。
     */
    @GetMapping("/nodes")
    public Result<KbNodePageVO> nodes(@RequestParam(required = false) Long parentId,
                                      @RequestParam(required = false) Integer pageSize,
                                      @RequestParam(required = false) String pageToken,
                                      @RequestParam(required = false) String keyword) {
        return Result.success(service.nodes(parentId, pageSize, pageToken, keyword));
    }

    /** 文档正文（唯一含 contentMd 的接口） */
    @GetMapping("/docs/{id}")
    public Result<KbDocDetailVO> doc(@PathVariable Long id) {
        return Result.success(service.doc(id));
    }

    /** 新建文档 / 文件夹，返回新节点 id */
    @PostMapping("/nodes")
    @RequirePerm("ai:kb:edit")
    public Result<Long> create(@RequestBody KbNodeCreateReq req) {
        return Result.success(service.create(req, operator()));
    }

    /** 重命名节点（DOC / FOLDER 通用） */
    @PutMapping("/nodes/{id}")
    @RequirePerm("ai:kb:edit")
    public Result<Void> rename(@PathVariable Long id, @RequestBody TitleRequest req) {
        service.rename(id, req.getTitle(), operator());
        return Result.success();
    }

    /** 保存标题 / 摘要 / 正文，后端据正文重建 INLINE 附件关系 */
    @PutMapping("/docs/{id}")
    @RequirePerm("ai:kb:edit")
    public Result<Void> update(@PathVariable Long id, @RequestBody KbDocUpdateReq req) {
        service.update(id, req, operator());
        return Result.success();
    }

    /** 移动节点：后端防环 + 重算自身与子树 path + 重排 sort_no */
    @PutMapping("/nodes/{id}/move")
    @RequirePerm("ai:kb:edit")
    public Result<Void> move(@PathVariable Long id, @RequestBody KbNodeMoveReq req) {
        service.move(id, req, operator());
        return Result.success();
    }

    /** 删除节点（存在子节点时后端拒绝） */
    @DeleteMapping("/nodes/{id}")
    @RequirePerm("ai:kb:edit")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id, operator());
        return Result.success();
    }

    /** 发布：P0 不接审核流，直接生效；version +1、旧切片失效、ORIGINAL 转 EXPIRED */
    @PostMapping("/docs/{id}/publish")
    @RequirePerm("ai:kb:publish")
    public Result<Void> publish(@PathVariable Long id) {
        service.publish(id, operator());
        return Result.success();
    }

    private String operator() {
        return SecurityContextUtils.currentUsername();
    }

    @Getter
    @Setter
    public static class TitleRequest {
        private String title;
    }
}
