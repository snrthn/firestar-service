package com.firestar.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.ai.core.ChatProviderManager;
import com.firestar.ai.crypto.CryptoService;
import com.firestar.ai.dto.ModelConfigMasked;
import com.firestar.ai.entity.ModelConfig;
import com.firestar.ai.mapper.ModelConfigMapper;
import com.firestar.annotation.Auditable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型配置服务（设计 §5，V214 起按 model_type 分类型管理）。
 *
 * <p>密钥（bot_id / api_key / extra_json）入库即加密（enc:v1: 前缀 + 国密 SM4-GCM），
 * 页面回显一律掩码（仅告知"是否已设置"），运行期解密仅在构建 Provider 时发生，不落前端。
 * 保存时某敏感字段为空 → 保留旧密文（不覆盖）；设为 active → 取消同类型其它行的 active。</p>
 *
 * <p><b>类型隔离（V214 核心约束）</b>：所有查询/唯一性/生效行都必须带 model_type 条件。
 * 同一 provider（如 dashscope）可同时存在 chat/embedding/rerank/tts 多行，互不干扰。
 * is_active 语义为「每类型唯一」，由本类 ensureUniqueActive 按类型置 0（DB 唯一键只约束 model_type+provider）。</p>
 *
 * <p>审计：create / update / delete 均标 {@link Auditable}（before/after 仅快照非敏感字段
 * modelType/provider/baseUrl/model/active，密钥永远不进审计日志）。保存使某行 active=1 后调用
 * {@link ChatProviderManager#reload()} 热切换运行期供应商（chat 类型）；其它类型的重载由各自 Manager 订阅。</p>
 */
@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);

    /** 支持的模型类型（与字典 ai_model_type / V214 一致）。 */
    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_EMBEDDING = "embedding";
    public static final String TYPE_RERANK = "rerank";
    public static final String TYPE_TTS = "tts";

    private final ModelConfigMapper mapper;
    private final CryptoService cryptoService;
    private final ChatProviderManager providerManager;

    public ModelConfigService(ModelConfigMapper mapper, CryptoService cryptoService,
                              ChatProviderManager providerManager) {
        this.mapper = mapper;
        this.cryptoService = cryptoService;
        this.providerManager = providerManager;
    }

    /** 指定类型的当前生效行（每类型运行期真值）；无则返回 null。 */
    public ModelConfig getActive(String modelType) {
        return mapper.selectOne(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getModelType, modelType)
                .eq(ModelConfig::getActive, 1));
    }

    /** 兼容旧调用：默认 chat 类型。 */
    public ModelConfig getActive() {
        return getActive(TYPE_CHAT);
    }

    public List<ModelConfig> listAll() {
        return mapper.selectList(null);
    }

    /** 按类型列出（前端可整体传回，前端再做类型分组展示）。 */
    public List<ModelConfig> listByType(String modelType) {
        return mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getModelType, modelType));
    }

    /** 脱敏后列表（供前端【模型配置】页展示） */
    public List<ModelConfigMasked> listMasked() {
        return listAll().stream().map(this::mask).toList();
    }

    @Auditable(operationType = "CREATE", targetType = "AI_MODEL_CONFIG",
            targetIdSpEl = "#result", entityClass = ModelConfig.class,
            fields = {"modelType", "provider", "baseUrl", "model", "active"})
    public Long create(ModelConfig input, String operator) {
        String type = normalizeType(input.getModelType());
        ModelConfig toSave = new ModelConfig();
        toSave.setModelType(type);
        toSave.setProvider(input.getProvider());
        toSave.setBaseUrl(input.getBaseUrl());
        toSave.setModel(input.getModel());
        toSave.setBotId(blank(input.getBotId()) ? null : cryptoService.encrypt(input.getBotId()));
        toSave.setApiKey(blank(input.getApiKey()) ? null : cryptoService.encrypt(input.getApiKey()));
        toSave.setExtraJson(blank(input.getExtraJson()) ? null : cryptoService.encrypt(input.getExtraJson()));
        toSave.setActive(zeroIfNull(input.getActive()));
        toSave.setCreatedBy(operator);
        toSave.setUpdatedBy(operator);
        // 唯一性按 (model_type, provider) 校验：同 provider 不同类型允许共存
        assertProviderUnique(type, input.getProvider(), null);
        mapper.insert(toSave);

        if (toSave.getActive() == 1) {
            ensureUniqueActive(type, toSave.getId());
            if (TYPE_CHAT.equals(type)) {
                providerManager.reload();
            }
        }
        return toSave.getId();
    }

    @Auditable(operationType = "UPDATE", targetType = "AI_MODEL_CONFIG",
            targetIdSpEl = "#id", entityClass = ModelConfig.class,
            fields = {"modelType", "provider", "baseUrl", "model", "active"})
    public void update(Long id, ModelConfig input, String operator) {
        ModelConfig old = mapper.selectById(id);
        if (old == null) {
            throw new IllegalArgumentException("模型配置不存在: " + id);
        }
        // model_type 不允许修改：改类型等于换一条配置，且会影响唯一键/消费方；引导删除重建
        String type = normalizeType(old.getModelType());
        ModelConfig toSave = new ModelConfig();
        toSave.setId(id);
        toSave.setModelType(type);
        toSave.setProvider(input.getProvider());
        toSave.setBaseUrl(input.getBaseUrl());
        toSave.setModel(input.getModel());
        // 敏感字段：非空才加密更新；空 = 保留旧密文
        toSave.setBotId(blank(input.getBotId()) ? old.getBotId() : cryptoService.encrypt(input.getBotId()));
        toSave.setApiKey(blank(input.getApiKey()) ? old.getApiKey() : cryptoService.encrypt(input.getApiKey()));
        toSave.setExtraJson(blank(input.getExtraJson()) ? old.getExtraJson() : cryptoService.encrypt(input.getExtraJson()));
        toSave.setActive(zeroIfNull(input.getActive()));
        toSave.setUpdatedBy(operator);
        assertProviderUnique(type, input.getProvider(), id);
        mapper.updateById(toSave);

        if (toSave.getActive() == 1) {
            ensureUniqueActive(type, id);
        }
        // 任意 chat 配置变更都热重载。关键修复：禁用(active=0)也必须立即生效——
        // 否则缓存的 ChatProvider 不刷新，用户点「停用」后对话仍走旧 provider（已实测）。
        if (TYPE_CHAT.equals(type)) {
            providerManager.reload();
        }
    }

    @Auditable(operationType = "DELETE", targetType = "AI_MODEL_CONFIG",
            targetIdSpEl = "#id", entityClass = ModelConfig.class,
            fields = {"modelType", "provider", "active"})
    public void delete(Long id) {
        ModelConfig old = mapper.selectById(id);
        boolean wasActive = old != null && old.getActive() != null && old.getActive() == 1;
        mapper.deleteById(id);
        if (wasActive && old != null && TYPE_CHAT.equals(normalizeType(old.getModelType()))) {
            providerManager.reload();
        }
    }

    /**
     * 保持每类型唯一生效行：将同类型其它 active=1 的行置 0。
     * ⚠️ 不能写 {@code UPDATE ... WHERE id <> ?} 这类无主键等值的批量改 ——
     * 项目装配了 BlockAttackInnerInterceptor，会拦"非主键等值"的 UPDATE/DELETE。
     * 故先按 active=1 且非自身、同类型查出待清零行，逐行 updateById（主键等值）。
     */
    private void ensureUniqueActive(String modelType, Long keepId) {
        List<ModelConfig> toClear = mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getModelType, modelType)
                .eq(ModelConfig::getActive, 1)
                .ne(ModelConfig::getId, keepId));
        for (ModelConfig c : toClear) {
            ModelConfig patch = new ModelConfig();
            patch.setId(c.getId());
            patch.setActive(0);
            mapper.updateById(patch); // 主键等值，过 BlockAttackInnerInterceptor
        }
    }

    /** (model_type, provider) 唯一性校验；exceptId 非空时（更新场景）排除自身。 */
    private void assertProviderUnique(String modelType, String provider, Long exceptId) {
        LambdaQueryWrapper<ModelConfig> w = new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getModelType, modelType)
                .eq(ModelConfig::getProvider, provider);
        if (exceptId != null) {
            w.ne(ModelConfig::getId, exceptId);
        }
        Long cnt = mapper.selectCount(w);
        if (cnt != null && cnt > 0) {
            throw new IllegalArgumentException(
                    "配置已存在(类型=" + modelType + ", 供应商=" + provider
                            + ")：同一类型下每个供应商仅允许一条配置，请编辑现有配置而非重复新增");
        }
    }

    /** 归一化类型：空值按 chat，非法值 fail-fast（与字典 ai_model_type 对齐）。 */
    public static String normalizeType(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return TYPE_CHAT;
        }
        String t = modelType.trim().toLowerCase();
        if (!TYPE_CHAT.equals(t) && !TYPE_EMBEDDING.equals(t)
                && !TYPE_RERANK.equals(t) && !TYPE_TTS.equals(t)) {
            throw new IllegalArgumentException("未知模型类型: " + modelType + "（可选: chat/embedding/rerank/tts）");
        }
        return t;
    }

    private ModelConfigMasked mask(ModelConfig c) {
        return new ModelConfigMasked(
                c.getId(), normalizeType(c.getModelType()), c.getProvider(), c.getActive(),
                c.getBaseUrl(), c.getModel(),
                !blank(c.getBotId()), !blank(c.getApiKey()), !blank(c.getExtraJson()),
                decryptExtra(c.getExtraJson()));
    }

    /**
     * extra_json 回显明文（配置参数，非密钥）。
     *
     * <p>与 api_key / bot_id 的「只回显是否已配置」不同：extra 存的是用户必须看得见
     * 才能改的参数（TTS 的 voice/format/sample_rate、embedding 的 dims）；前端编辑
     * 弹窗按模型类型渲染结构化表单时，也必须拿到原值才能 merge（只覆盖结构化管理的
     * 字段，保留其余字段），否则用户一打开就是空表单，误填即覆盖丢失。</p>
     *
     * <p>库内是密文（enc:v1:），这里返回解密后的明文 JSON 文本；历史明文数据
     * 由 {@code CryptoService.decrypt} 兼容原样返回。</p>
     */
    private String decryptExtra(String extraJson) {
        if (blank(extraJson)) {
            return null;
        }
        try {
            return cryptoService.decrypt(extraJson);
        } catch (Exception e) {
            log.warn("[AI] extra_json 解密失败（回显为空）: {}", e.getMessage());
            return null;
        }
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private int zeroIfNull(Integer v) {
        return v == null ? 0 : v;
    }
}
