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
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <p><b>展示顺序（V215）</b>：列表按 sort_no 升序（同号按 id），数值即展示序号（1..N 连续）。
 * 新增默认追加到末尾（表单也可直接填），「上移/下移」走 {@link #reorder(List, String)}
 * 按整表 id 顺序重写 sort_no。顺序只影响展示，不影响运行期生效行（is_active 决定）。</p>
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
        // V215：列表顺序 = 管理员在页面调好的 sort_no 升序；同号（存量/并发插入）再按 id 兜底
        return mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                .orderByAsc(ModelConfig::getSortNo)
                .orderByAsc(ModelConfig::getId));
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
            fields = {"modelType", "provider", "baseUrl", "model", "active", "sortNo"})
    public Long create(ModelConfig input, String operator) {
        String type = normalizeType(input.getModelType());
        ModelConfig toSave = new ModelConfig();
        toSave.setModelType(type);
        toSave.setProvider(input.getProvider());
        toSave.setBaseUrl(input.getBaseUrl());
        toSave.setModel(input.getModel());
        // V215：表单没填排序号 → 追加到列表末尾（max+1），不打断管理员已调好的顺序
        toSave.setSortNo(input.getSortNo() != null ? input.getSortNo() : nextSortNo());
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
            fields = {"modelType", "provider", "baseUrl", "model", "active", "sortNo"})
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
        // V215：排序号表单可填；null = 保持原值（MyBatis-Plus updateById 跳过 null 字段）
        toSave.setSortNo(input.getSortNo());
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
     * 整表重排（V215，页面「上移/下移」唯一入口）。
     *
     * <p>入参是<b>页面当前展示的完整 id 顺序</b>，按 {@code index + 1} 回写 sort_no
     * （1..N 连续，数值即展示序号）。校验 ids 与库内行集合完全一致：多一人同时新增/
     * 删除配置，用「部分 id」重排会把未知行挤到随机位置，所以宁可报错让前端刷新重来。</p>
     *
     * <p>只更新 sort_no 真正变化的行（BlockAttackInnerInterceptor 要求主键等值更新，
     * 故逐行 updateById）；不做 chat 热重载 —— 顺序不影响运行期生效行。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Auditable(operationType = "UPDATE", targetType = "AI_MODEL_CONFIG",
            remark = "调整 AI 模型配置展示顺序")
    public void reorder(List<Long> ids, String operator) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("排序参数不能为空");
        }
        List<Long> ordered = ids.stream().filter(Objects::nonNull).toList();
        if (ordered.size() != ids.size()) {
            throw new IllegalArgumentException("排序参数包含空 id");
        }
        if (new HashSet<>(ordered).size() != ordered.size()) {
            throw new IllegalArgumentException("排序参数存在重复 id");
        }

        List<ModelConfig> all = listAll();
        Set<Long> dbIds = all.stream().map(ModelConfig::getId).collect(Collectors.toSet());
        if (!dbIds.equals(new HashSet<>(ordered))) {
            throw new IllegalArgumentException("配置列表已变化（可能有人同时新增/删除了配置），请刷新后重试");
        }

        Map<Long, Integer> current = all.stream()
                .collect(Collectors.toMap(ModelConfig::getId, c -> c.getSortNo() == null ? 0 : c.getSortNo()));
        int seq = 1;
        for (Long id : ordered) {
            if (current.getOrDefault(id, 0) != seq) {
                ModelConfig patch = new ModelConfig();
                patch.setId(id);
                patch.setSortNo(seq);
                patch.setUpdatedBy(operator);
                mapper.updateById(patch);
            }
            seq++;
        }
        log.info("[AI] 模型配置顺序已重排，共 {} 行，操作人={}", ordered.size(), operator);
    }

    /** 追加到末尾用的排序号：当前最大值 + 1（空表从 1 开始）。 */
    private int nextSortNo() {
        return listAll().stream()
                .mapToInt(c -> c.getSortNo() == null ? 0 : c.getSortNo())
                .max()
                .orElse(0) + 1;
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
                decryptExtra(c.getExtraJson()),
                c.getSortNo() == null ? 0 : c.getSortNo());
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
