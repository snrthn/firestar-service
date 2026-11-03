package com.firestar.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.ai.entity.AiPromptConfig;
import com.firestar.ai.mapper.AiPromptConfigMapper;
import com.firestar.annotation.Auditable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 提示词方案服务（设计 §10 系统指令运维化）。
 *
 * <p>多套提示词并存，is_active=1 全局唯一生效（与模型配置 is_active 语义一致）。
 * 启用哪套哪套在对话中实时生效：{@link #getActiveContent()} 供 AiChatController 注入 system 消息，
 * 无生效行时返回 null，由调用方回退 AiProperties 常量（向后兼容）。
 * 任意变更（增/改/删/启用）后 {@link #reload()} 清缓存，下次对话惰性重建，无需重启。</p>
 *
 * <p>审计：create / update / delete / 启用 均标 {@link Auditable}（仅快照 name/active，content 不入审计
 * 以避免超长文本污染审计表；如需追溯可结合业务侧版本管理）。</p>
 */
@Service
public class PromptConfigService {

    private final AiPromptConfigMapper mapper;

    public PromptConfigService(AiPromptConfigMapper mapper) {
        this.mapper = mapper;
    }

    /** 当前生效提示词正文缓存（is_active=1 唯一生效）；null = 无生效行（调用方回退默认） */
    private volatile String cachedActiveContent;
    private volatile boolean cacheLoaded = false;

    /**
     * 取当前生效提示词正文。懒加载 + 缓存；无生效行返回 null。
     * 线程安全：双检锁，避免并发重复查库。
     */
    public String getActiveContent() {
        if (cacheLoaded) {
            return cachedActiveContent;
        }
        synchronized (this) {
            if (cacheLoaded) {
                return cachedActiveContent;
            }
            AiPromptConfig active = mapper.selectOne(
                    new LambdaQueryWrapper<AiPromptConfig>().eq(AiPromptConfig::getActive, 1));
            cachedActiveContent = active == null ? null : active.getContent();
            cacheLoaded = true;
            return cachedActiveContent;
        }
    }

    /** 清缓存（增/改/删/启用后调用，下次对话惰性重建） */
    public void reload() {
        cachedActiveContent = null;
        cacheLoaded = false;
    }

    public List<AiPromptConfig> listAll() {
        return mapper.selectList(null);
    }

    @Auditable(operationType = "CREATE", targetType = "AI_PROMPT",
            targetIdSpEl = "#result", entityClass = AiPromptConfig.class,
            fields = {"name", "active"})
    public Long create(AiPromptConfig input, String operator) {
        AiPromptConfig toSave = new AiPromptConfig();
        toSave.setName(input.getName());
        toSave.setContent(input.getContent());
        toSave.setActive(zeroIfNull(input.getActive()));
        toSave.setCreatedBy(operator);
        toSave.setUpdatedBy(operator);
        mapper.insert(toSave);
        if (toSave.getActive() == 1) {
            ensureUniqueActive(toSave.getId());
            reload();
        }
        return toSave.getId();
    }

    @Auditable(operationType = "UPDATE", targetType = "AI_PROMPT",
            targetIdSpEl = "#id", entityClass = AiPromptConfig.class,
            fields = {"name", "active"})
    public void update(Long id, AiPromptConfig input, String operator) {
        AiPromptConfig old = mapper.selectById(id);
        if (old == null) {
            throw new IllegalArgumentException("提示词配置不存在: " + id);
        }
        AiPromptConfig toSave = new AiPromptConfig();
        toSave.setId(id);
        toSave.setName(input.getName());
        toSave.setContent(input.getContent());
        toSave.setActive(zeroIfNull(input.getActive()));
        toSave.setUpdatedBy(operator);
        mapper.updateById(toSave);
        if (toSave.getActive() == 1) {
            ensureUniqueActive(id);
        }
        // 任意变更都热重载：避免缓存的旧生效行残留
        reload();
    }

    @Auditable(operationType = "DELETE", targetType = "AI_PROMPT",
            targetIdSpEl = "#id", entityClass = AiPromptConfig.class,
            fields = {"name", "active"})
    public void delete(Long id) {
        AiPromptConfig old = mapper.selectById(id);
        boolean wasActive = old != null && old.getActive() != null && old.getActive() == 1;
        mapper.deleteById(id);
        if (wasActive) {
            reload(); // 生效行被删 → 回退默认常量
        }
    }

    /** 设为唯一生效行：清其它 active=1，置本行 active=1，热重载 */
    @Auditable(operationType = "UPDATE", targetType = "AI_PROMPT",
            targetIdSpEl = "#id", entityClass = AiPromptConfig.class,
            fields = {"active"})
    public void setActive(Long id) {
        AiPromptConfig old = mapper.selectById(id);
        if (old == null) {
            throw new IllegalArgumentException("提示词配置不存在: " + id);
        }
        ensureUniqueActive(id);
        AiPromptConfig patch = new AiPromptConfig();
        patch.setId(id);
        patch.setActive(1);
        mapper.updateById(patch);
        reload();
    }

    /**
     * 保持唯一生效行：将其它所有 active=1 的行置 0。
     * ⚠️ 不能写 UPDATE ... WHERE id <> ?（非主键等值）——项目装配了 BlockAttackInnerInterceptor
     * （MybatisPlusConfig），会拦"非主键等值"的批量改。故先查出待清零行，逐行 updateById（主键等值）。
     */
    private void ensureUniqueActive(Long keepId) {
        List<AiPromptConfig> toClear = mapper.selectList(new LambdaQueryWrapper<AiPromptConfig>()
                .eq(AiPromptConfig::getActive, 1)
                .ne(AiPromptConfig::getId, keepId));
        for (AiPromptConfig c : toClear) {
            AiPromptConfig patch = new AiPromptConfig();
            patch.setId(c.getId());
            patch.setActive(0);
            mapper.updateById(patch); // 主键等值，过 BlockAttackInnerInterceptor
        }
    }

    private int zeroIfNull(Integer v) {
        return v == null ? 0 : v;
    }
}
