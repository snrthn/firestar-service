package com.firestar.ai.controller;

import com.firestar.ai.entity.AiPromptConfig;
import com.firestar.ai.service.PromptConfigService;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.common.SecurityContextUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 提示词管理接口（设计 §10）。【提示词管理】页后端：
 * <ul>
 *   <li>列表返回完整方案（含 content，系统提示词非密钥，可下发供前端编辑）；</li>
 *   <li>新增/更新/删除/启用均二次审计（见 PromptConfigService 的 {@code @Auditable}）；</li>
 *   <li>使某行 active=1 后运行期热切换生效提示词，无需重启（{@link PromptConfigService#reload()}）。</li>
 * </ul>
 * 权限码三段式：查看 = ai:prompt:view，变更 = ai:prompt:edit，启用生效 = ai:prompt:use。
 */
@RestController
@RequestMapping("/ai/prompt-config")
@RequirePerm("ai:prompt:view")
public class AiPromptController {

    private final PromptConfigService service;

    public AiPromptController(PromptConfigService service) {
        this.service = service;
    }

    /** 方案列表 */
    @GetMapping
    public Result<List<AiPromptConfig>> list() {
        return Result.success(service.listAll());
    }

    /** 新建（active=1 时热切换生效提示词） */
    @PostMapping
    @RequirePerm("ai:prompt:edit")
    public Result<Long> create(@RequestBody AiPromptConfig req) {
        return Result.success(service.create(toEntity(req), SecurityContextUtils.currentUsername()));
    }

    /** 更新（任意变更均热重载） */
    @PutMapping
    @RequirePerm("ai:prompt:edit")
    public Result<Void> update(@RequestBody AiPromptConfig req) {
        if (req.getId() == null) {
            return Result.error("缺少 id");
        }
        service.update(req.getId(), toEntity(req), SecurityContextUtils.currentUsername());
        return Result.success();
    }

    /** 删除（删除生效行则回退默认常量） */
    @DeleteMapping("/{id}")
    @RequirePerm("ai:prompt:edit")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }

    /** 设为生效行（敏感：影响线上对话行为） */
    @PostMapping("/{id}/activate")
    @RequirePerm("ai:prompt:use")
    public Result<Void> activate(@PathVariable Long id) {
        service.setActive(id);
        return Result.success();
    }

    /** 显式热重载（手动复位缓存） */
    @PostMapping("/reload")
    @RequirePerm("ai:prompt:edit")
    public Result<Void> reload() {
        service.reload();
        return Result.success();
    }

    private AiPromptConfig toEntity(AiPromptConfig req) {
        AiPromptConfig e = new AiPromptConfig();
        e.setId(req.getId());
        e.setName(req.getName());
        e.setContent(req.getContent());
        e.setActive(req.getActive());
        return e;
    }
}
