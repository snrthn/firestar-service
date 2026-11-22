package com.firestar.ai.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.ai.crypto.CryptoService;
import com.firestar.ai.entity.ModelConfig;
import com.firestar.ai.mapper.ModelConfigMapper;
import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ChatProvider;
import com.firestar.ai.spi.TurnRequest;
import com.firestar.ai.tool.ToolExecutor;
import com.firestar.entity.DictData;
import com.firestar.mapper.DictDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 供应商管理器（设计 §4.2 + §4.3 熔断降级）。
 *
 * <p>职责：
 * <ul>
 *   <li>按 DB {@code sys_model_config.is_active} 行（运行期唯一真源）构建并缓存当前生效的 ChatProvider；</li>
 *   <li>{@link #reload()} 在【模型配置】页保存时触发，解密重建并原子替换，无需重启（运行期热切换）；</li>
 *   <li>内置熔断器：连续失败达阈值进入 OPEN，后续请求直接下发<b>降级兜底帧</b>（普通搜索 / 报错），
 *       不再打供应商；冷却期后半开探测，恢复后自动回到 CLOSED（扣子积分续费即自动恢复）。</li>
 * </ul>
 * 供应商只干一件事，本管理器不感知业务逻辑。</p>
 *
 * <p>供应商注册表：能力边界以 {@code Map<String, Function<BuildCtx, ChatProvider>>} 表达，
 * 新增供应商 = 在注册表加一行 + 字典 ai_provider 加一条数据（前端下拉零改动）。
 * 字典声明了但注册表无实现的供应商，会在启动期 {@link #run(ApplicationArguments)} 告警，
 * 选择该供应商时运行时抛"未知供应商类型"。</p>
 */
@Service
public class ChatProviderManager implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatProviderManager.class);

    private final ModelConfigMapper modelConfigMapper;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final ToolExecutor toolExecutor;
    private final HttpClientFactory httpClientFactory;
    private final DictDataMapper dictDataMapper;

    /** 供应商注册表：provider 字符串 → 构建函数（捕获本 Manager 的 final 依赖字段） */
    private final Map<String, Function<BuildCtx, ChatProvider>> registry;

    /** 构建 ChatProvider 所需的统一入参（各 Provider 构造函数签名不同，从中取子集） */
    private record BuildCtx(String baseUrl, String model, String botId, String apiKey, String extraJson) {}

    private volatile ChatProvider active;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long openSince;

    private static final int FAILURE_THRESHOLD = 5;
    private static final long COOLDOWN_MS = 10 * 60 * 1000L;

    public ChatProviderManager(ModelConfigMapper modelConfigMapper,
                               CryptoService cryptoService, ObjectMapper objectMapper,
                               ToolExecutor toolExecutor, HttpClientFactory httpClientFactory,
                               DictDataMapper dictDataMapper) {
        this.modelConfigMapper = modelConfigMapper;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
        this.toolExecutor = toolExecutor;
        this.httpClientFactory = httpClientFactory;
        this.dictDataMapper = dictDataMapper;
        // 构造器内初始化注册表：此时上方 final 字段均已赋值，lambda 捕获 this 运行时已完整。
        this.registry = Map.of(
                // 端插件工具执行：ToolExecutor 位于 biz，经 core 的 ToolExecutionPort 接口注入
                "coze", c -> new CozeProvider(c.baseUrl(), c.botId(), c.apiKey(), this.objectMapper, this.toolExecutor, this.httpClientFactory),
                "openai", c -> new OpenAIProvider(c.baseUrl(), c.apiKey(), c.model(), this.objectMapper, this.toolExecutor, this.httpClientFactory),
                // DeepSeek 为 OpenAI 兼容协议，复用 OpenAIProvider 的 SSE 解析 + 工具调用循环，仅 name 不同
                "deepseek", c -> new DeepSeekProvider(c.baseUrl(), c.apiKey(), c.model(), this.objectMapper, this.toolExecutor, this.httpClientFactory),
                "ollama", c -> new OllamaProvider(c.baseUrl(), c.model(), this.objectMapper),
                // Anthropic 原生 Messages API（/v1/messages），extraJson 传 maxTokens / anthropicVersion
                "anthropic", c -> new AnthropicProvider(c.baseUrl(), c.apiKey(), c.model(), c.extraJson(), this.objectMapper, this.toolExecutor, this.httpClientFactory)
        );
    }

    /** 当前生效供应商（DB is_active 为唯一真源，无生效行则报错） */
    public ChatProvider activeProvider() {
        ChatProvider p = active;
        if (p == null) {
            synchronized (this) {
                p = active;
                if (p == null) {
                    p = buildActive();
                    active = p;
                }
            }
        }
        return p;
    }

    /** 【模型配置】页保存后调用：重建并原子替换，清除熔断状态 */
    public void reload() {
        active = null;
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
    }

    public String createSession(String userRef) throws Exception {
        return activeProvider().createSession(userRef);
    }

    /** 带熔断 / 降级的流式对话。OPEN 期内直接下发 fallback 帧，不触供应商 */
    public void streamWithFallback(TurnRequest req, Consumer<ChatChunk> onChunk) throws Exception {
        if (state.get() == State.OPEN
                && System.currentTimeMillis() - openSince < COOLDOWN_MS) {
            onChunk.accept(ChatChunk.fallback("error", "AI_PROVIDER_UNAVAILABLE"));
            return;
        }
        if (state.get() == State.OPEN) {
            state.set(State.HALF_OPEN); // 冷却结束，半开探测
        }
        try {
            activeProvider().streamChat(req, onChunk);
            consecutiveFailures.set(0);
            state.set(State.CLOSED);
        } catch (Exception e) {
            int f = consecutiveFailures.incrementAndGet();
            if (f >= FAILURE_THRESHOLD || state.get() == State.HALF_OPEN) {
                state.set(State.OPEN);
                openSince = System.currentTimeMillis();
            }
            throw e;
        }
    }

    /**
     * 构建当前生效供应商。DB（sys_model_config.is_active=1）是唯一真源，不再回退环境变量默认供应商。
     * 无生效行时明确报错，提示用户在【模型配置】页启用/新增，避免静默拿空凭证发起必败请求。
     */
    private ChatProvider buildActive() {
        // V214：必须限定 model_type='chat'，否则 dashscope 的 embedding/rerank/tts 生效行
        // 会被误当成对话供应商构建（同一 provider 现在可存在多类型配置行）。
        ModelConfig cfg = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getModelType, "chat")
                .eq(ModelConfig::getActive, 1));
        if (cfg != null) {
            return build(cfg.getProvider(), cfg.getBaseUrl(), cfg.getModel(),
                    cryptoService.decrypt(cfg.getBotId()), cryptoService.decrypt(cfg.getApiKey()),
                    cryptoService.decrypt(cfg.getExtraJson()));
        }
        // 没有 is_active=1 的生效行：区分「已存在但被停用」与「全新库零行」，但两者都明确报错，
        // 不再静默回退到 env 默认空凭证（coze.pat 未配置 → Bearer null → 秒 401 / 代理一抖就超时）。
        long total = modelConfigMapper.selectCount(
                new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getModelType, "chat"));
        if (total > 0) {
            throw new IllegalStateException("当前没有启用的 AI 模型，请在【模型配置】中启用一个模型");
        }
        throw new IllegalStateException("尚未配置任何 AI 模型，请在【模型配置】中新增并启用一个模型后再使用 AI 助手");
    }

    private ChatProvider build(String type, String baseUrl, String model, String botId, String apiKey, String extraJson) {
        Function<BuildCtx, ChatProvider> factory = registry.get(type);
        if (factory == null) {
            throw new IllegalStateException("未知供应商类型: " + type
                    + "（请确认已在 ChatProviderManager 注册表登记，且字典 ai_provider 数据与之对齐）");
        }
        return factory.apply(new BuildCtx(baseUrl, model, botId, apiKey, extraJson));
    }

    /**
     * 仅用于非对话模型类型（embedding / rerank / tts）的供应商。
     * V214 起 ai_provider 字典由四类模型共用，dashscope 只实现 Embedding/Rerank/TTS，
     * 不实现 ChatProvider —— 它出现在字典里是正常的，不应触发「未登记实现」告警。
     */
    private static final Set<String> NON_CHAT_ONLY_PROVIDERS = Set.of("dashscope");

    /**
     * 启动期字典对齐校验（防御性）：字典 ai_provider 声明了某供应商但注册表无实现时告警。
     * 不阻断启动——字典表可能尚未就绪，或运营临时停用某字典项；真正约束在运行时 build()。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            List<DictData> declared = dictDataMapper.selectList(
                    new LambdaQueryWrapper<DictData>()
                            .eq(DictData::getTypeCode, "ai_provider")
                            .eq(DictData::getStatus, "ENABLED"));
            for (DictData d : declared) {
                if (NON_CHAT_ONLY_PROVIDERS.contains(d.getDictValue())) {
                    continue;
                }
                if (!registry.containsKey(d.getDictValue())) {
                    log.warn("[AI] 字典 ai_provider 声明了供应商 '{}' 但 ChatProviderManager 未登记对应实现，"
                            + "选择该供应商时运行时将报错", d.getDictValue());
                }
            }
        } catch (Exception e) {
            log.warn("[AI] 启动期字典对齐校验跳过（字典表可能尚未初始化）：{}", e.getMessage());
        }
    }

    private enum State { CLOSED, OPEN, HALF_OPEN }
}
