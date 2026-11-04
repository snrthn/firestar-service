package com.firestar.ai.controller;

import com.firestar.ai.core.ChatProviderManager;
import com.firestar.ai.dto.ModelConfigMasked;
import com.firestar.ai.dto.ModelConfigRequest;
import com.firestar.ai.entity.ModelConfig;
import com.firestar.ai.service.ModelConfigService;
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
 * AI 模型配置接口（设计 §5）。【模型配置】页后端：
 * <ul>
 *   <li>列表返回脱敏视图 {@link ModelConfigMasked}（密钥只给"是否设置"标记，绝不下发明文/密文）；</li>
 *   <li>新建/更新/删除均二次审计（见 ModelConfigService 的 {@code @Auditable}）；</li>
 *   <li>使某行 active=1 后运行期热切换供应商，无需重启（{@link ChatProviderManager#reload()}）。</li>
 * </ul>
 * 权限点沿用「一个 perm_code 管整个模块」约定：查看 = sys:modelConfig:view，变更 = sys:modelConfig:edit。
 */
@RestController
@RequestMapping("/ai/model-config")
@RequirePerm("sys:modelConfig:view")
public class ModelConfigController {

    private final ModelConfigService service;
    private final ChatProviderManager providerManager;

    public ModelConfigController(ModelConfigService service, ChatProviderManager providerManager) {
        this.service = service;
        this.providerManager = providerManager;
    }

    /** 脱敏列表 */
    @GetMapping
    public Result<List<ModelConfigMasked>> list() {
        return Result.success(service.listMasked());
    }

    /** 新建（active=1 时热切换供应商） */
    @PostMapping
    @RequirePerm("sys:modelConfig:edit")
    public Result<Long> create(@RequestBody ModelConfigRequest req) {
        Long id = service.create(toEntity(req), SecurityContextUtils.currentUsername());
        return Result.success(id);
    }

    /** 更新（active=1 时热切换供应商） */
    @PutMapping
    @RequirePerm("sys:modelConfig:edit")
    public Result<Void> update(@RequestBody ModelConfigRequest req) {
        if (req.id() == null) {
            return Result.error("缺少 id");
        }
        service.update(req.id(), toEntity(req), SecurityContextUtils.currentUsername());
        return Result.success();
    }

    /** 删除（删除生效行则回退 env 默认供应商） */
    @DeleteMapping("/{id}")
    @RequirePerm("sys:modelConfig:edit")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }

    /** 显式热重载（改了 active 行但想立即生效，或手动复位熔断器） */
    @PostMapping("/reload")
    @RequirePerm("sys:modelConfig:edit")
    public Result<Void> reload() {
        providerManager.reload();
        return Result.success();
    }

    private ModelConfig toEntity(ModelConfigRequest req) {
        ModelConfig e = new ModelConfig();
        e.setProvider(req.provider());
        e.setBaseUrl(req.baseUrl());
        e.setModel(req.model());
        e.setBotId(req.botId());
        e.setApiKey(req.apiKey());
        e.setExtraJson(req.extraJson());
        e.setActive(req.active());
        return e;
    }
}
