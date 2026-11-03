package com.firestar.ai.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.ai.crypto.CryptoService;
import com.firestar.ai.entity.ModelConfig;
import com.firestar.ai.mapper.ModelConfigMapper;
import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ChatProvider;
import com.firestar.ai.spi.TurnRequest;
import com.firestar.ai.tool.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
 */
@Service
public class ChatProviderManager {

    private final ModelConfigMapper modelConfigMapper;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final ToolExecutor toolExecutor;
    private final HttpClientFactory httpClientFactory;

    private volatile ChatProvider active;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long openSince;

    private static final int FAILURE_THRESHOLD = 5;
    private static final long COOLDOWN_MS = 10 * 60 * 1000L;

    public ChatProviderManager(ModelConfigMapper modelConfigMapper,
                               CryptoService cryptoService, ObjectMapper objectMapper,
                               ToolExecutor toolExecutor, HttpClientFactory httpClientFactory) {
        this.modelConfigMapper = modelConfigMapper;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
        this.toolExecutor = toolExecutor;
        this.httpClientFactory = httpClientFactory;
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
        ModelConfig cfg = modelConfigMapper.selectOne(
                new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getActive, 1));
        if (cfg != null) {
            return build(cfg.getProvider(), cfg.getBaseUrl(), cfg.getModel(),
                    cryptoService.decrypt(cfg.getBotId()), cryptoService.decrypt(cfg.getApiKey()));
        }
        // 没有 is_active=1 的生效行：区分「已存在但被停用」与「全新库零行」，但两者都明确报错，
        // 不再静默回退到 env 默认空凭证（coze.pat 未配置 → Bearer null → 秒 401 / 代理一抖就超时）。
        long total = modelConfigMapper.selectCount(new LambdaQueryWrapper<>());
        if (total > 0) {
            throw new IllegalStateException("当前没有启用的 AI 模型，请在【模型配置】中启用一个模型");
        }
        throw new IllegalStateException("尚未配置任何 AI 模型，请在【模型配置】中新增并启用一个模型后再使用 AI 助手");
    }

    private ChatProvider build(String type, String baseUrl, String model, String botId, String apiKey) {
        return switch (type) {
            case "coze" -> new CozeProvider(baseUrl, botId, apiKey, objectMapper, httpClientFactory);
            case "openai" -> new OpenAIProvider(baseUrl, apiKey, model, objectMapper, toolExecutor, httpClientFactory);
            // DeepSeek 为 OpenAI 兼容协议，复用 OpenAIProvider 的 SSE 解析 + 工具调用循环，仅 name 不同
            case "deepseek" -> new DeepSeekProvider(baseUrl, apiKey, model, objectMapper, toolExecutor, httpClientFactory);
            case "ollama" -> new OllamaProvider(baseUrl, model, objectMapper);
            default -> throw new IllegalStateException("未知供应商类型: " + type);
        };
    }

    private enum State { CLOSED, OPEN, HALF_OPEN }
}
