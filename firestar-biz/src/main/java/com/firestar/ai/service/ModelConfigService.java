package com.firestar.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.ai.core.ChatProviderManager;
import com.firestar.ai.crypto.CryptoService;
import com.firestar.ai.dto.ModelConfigMasked;
import com.firestar.ai.entity.ModelConfig;
import com.firestar.ai.mapper.ModelConfigMapper;
import com.firestar.annotation.Auditable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型配置服务（设计 §5）。
 *
 * <p>密钥（bot_id / api_key / extra_json）入库即加密（enc:v1: 前缀 + 国密 SM4-GCM），
 * 页面回显一律掩码（仅告知"是否已设置"），运行期解密仅在构建 ChatProvider 时发生，不落前端。
 * 保存时某敏感字段为空 → 保留旧密文（不覆盖）；设为 active → 取消其它行的 active（唯一生效行）。</p>
 *
 * <p>审计：create / update / delete 均标 {@link Auditable}（before/after 仅快照非敏感字段
 * provider/baseUrl/model/active，密钥永远不进审计日志）。保存使某行 active=1 后调用
 * {@link ChatProviderManager#reload()} 热切换运行期供应商，无需重启（设计 §4.2）。</p>
 */
@Service
public class ModelConfigService {

    private final ModelConfigMapper mapper;
    private final CryptoService cryptoService;
    private final ChatProviderManager providerManager;

    public ModelConfigService(ModelConfigMapper mapper, CryptoService cryptoService,
                              ChatProviderManager providerManager) {
        this.mapper = mapper;
        this.cryptoService = cryptoService;
        this.providerManager = providerManager;
    }

    /** 当前生效行（运行期真值）；无则返回 null，回退 env 默认 */
    public ModelConfig getActive() {
        return mapper.selectOne(new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getActive, 1));
    }

    public List<ModelConfig> listAll() {
        return mapper.selectList(null);
    }

    /** 脱敏后列表（供前端【模型配置】页展示） */
    public List<ModelConfigMasked> listMasked() {
        return listAll().stream().map(this::mask).toList();
    }

    @Auditable(operationType = "CREATE", targetType = "AI_MODEL_CONFIG",
            targetIdSpEl = "#result", entityClass = ModelConfig.class,
            fields = {"provider", "baseUrl", "model", "active"})
    public Long create(ModelConfig input, String operator) {
        ModelConfig toSave = new ModelConfig();
        toSave.setProvider(input.getProvider());
        toSave.setBaseUrl(input.getBaseUrl());
        toSave.setModel(input.getModel());
        toSave.setBotId(blank(input.getBotId()) ? null : cryptoService.encrypt(input.getBotId()));
        toSave.setApiKey(blank(input.getApiKey()) ? null : cryptoService.encrypt(input.getApiKey()));
        toSave.setExtraJson(blank(input.getExtraJson()) ? null : cryptoService.encrypt(input.getExtraJson()));
        toSave.setActive(zeroIfNull(input.getActive()));
        toSave.setCreatedBy(operator);
        toSave.setUpdatedBy(operator);
        // 提前查重：每个 provider 仅允许一条配置（V99 uk_provider 唯一键），
        // 撞键时抛清晰文案而非让全局 DuplicateKeyException 兜底成"单据编号重复"。
        assertProviderUnique(input.getProvider(), null);
        mapper.insert(toSave);

        if (toSave.getActive() == 1) {
            ensureUniqueActive(toSave.getId());
            providerManager.reload();
        }
        return toSave.getId();
    }

    @Auditable(operationType = "UPDATE", targetType = "AI_MODEL_CONFIG",
            targetIdSpEl = "#id", entityClass = ModelConfig.class,
            fields = {"provider", "baseUrl", "model", "active"})
    public void update(Long id, ModelConfig input, String operator) {
        ModelConfig old = mapper.selectById(id);
        if (old == null) {
            throw new IllegalArgumentException("模型配置不存在: " + id);
        }
        ModelConfig toSave = new ModelConfig();
        toSave.setId(id);
        toSave.setProvider(input.getProvider());
        toSave.setBaseUrl(input.getBaseUrl());
        toSave.setModel(input.getModel());
        // 敏感字段：非空才加密更新；空 = 保留旧密文
        toSave.setBotId(blank(input.getBotId()) ? old.getBotId() : cryptoService.encrypt(input.getBotId()));
        toSave.setApiKey(blank(input.getApiKey()) ? old.getApiKey() : cryptoService.encrypt(input.getApiKey()));
        toSave.setExtraJson(blank(input.getExtraJson()) ? old.getExtraJson() : cryptoService.encrypt(input.getExtraJson()));
        toSave.setActive(zeroIfNull(input.getActive()));
        toSave.setUpdatedBy(operator);
        // 更新时改 provider 也可能撞已有行（排除自身）
        assertProviderUnique(input.getProvider(), id);
        mapper.updateById(toSave);

        if (toSave.getActive() == 1) {
            ensureUniqueActive(id);
        }
        // 任意模型配置变更都热重载。关键修复：禁用(active=0)也必须立即生效——
        // 否则缓存的 ChatProvider 不刷新，用户点「停用」后对话仍走旧 provider（已实测「停用不直接生效」）。
        // 重载只是把 active 置 null，下次对话惰性重建；无生效行时由 ChatProviderManager 给出清晰报错而非超时。
        providerManager.reload();
    }

    @Auditable(operationType = "DELETE", targetType = "AI_MODEL_CONFIG",
            targetIdSpEl = "#id", entityClass = ModelConfig.class,
            fields = {"provider", "active"})
    public void delete(Long id) {
        ModelConfig old = mapper.selectById(id);
        boolean wasActive = old != null && old.getActive() != null && old.getActive() == 1;
        mapper.deleteById(id);
        if (wasActive) {
            providerManager.reload(); // 生效行被删 → 回退 env 默认供应商
        }
    }

    /**
     * 保持唯一生效行：将其它所有 active=1 的行置 0。
     * ⚠️ 不能写 {@code UPDATE ... WHERE id <> ?} 这类无主键等值的批量改——
     * 项目装配了 BlockAttackInnerInterceptor（MybatisPlusConfig 第47行），会拦"非主键等值"的
     * UPDATE/DELETE（防误改全表）。故改为先按 active=1 且非自身查出待清零行，逐行 updateById（主键等值）。
     */
    private void ensureUniqueActive(Long keepId) {
        List<ModelConfig> toClear = mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getActive, 1)
                .ne(ModelConfig::getId, keepId));
        for (ModelConfig c : toClear) {
            ModelConfig patch = new ModelConfig();
            patch.setId(c.getId());
            patch.setActive(0);
            mapper.updateById(patch); // 主键等值，过 BlockAttackInnerInterceptor
        }
    }

    /** provider 唯一性校验：V99 的 uk_provider 约束每个供应商仅一条配置；
     *  exceptId 非空时（更新场景）排除自身，避免与自己的旧值冲突。 */
    private void assertProviderUnique(String provider, Long exceptId) {
        LambdaQueryWrapper<ModelConfig> w = new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getProvider, provider);
        if (exceptId != null) {
            w.ne(ModelConfig::getId, exceptId);
        }
        Long cnt = mapper.selectCount(w);
        if (cnt != null && cnt > 0) {
            throw new IllegalArgumentException(
                    "供应商配置已存在(provider=" + provider + ")：每个供应商仅允许一条配置，请编辑现有配置而非重复新增");
        }
    }

    private ModelConfigMasked mask(ModelConfig c) {
        return new ModelConfigMasked(
                c.getId(), c.getProvider(), c.getActive(), c.getBaseUrl(), c.getModel(),
                !blank(c.getBotId()), !blank(c.getApiKey()), !blank(c.getExtraJson()));
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private int zeroIfNull(Integer v) {
        return v == null ? 0 : v;
    }
}
