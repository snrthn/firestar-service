package com.firestar.ai.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.ai.config.AiProperties;
import com.firestar.ai.crypto.CryptoService;
import com.firestar.ai.entity.ModelConfig;
import com.firestar.ai.mapper.ModelConfigMapper;
import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ChatProvider;
import com.firestar.ai.spi.TurnRequest;
import com.firestar.ai.tool.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 供应商管理器（设计 §4.2 + §4.3 熔断降级）。
 *
 * <p>职责：
 * <ul>
 *   <li>按 DB {@code sys_model_config.is_active} 行（运行期真值）或 env 默认值，构建并缓存当前生效的 ChatProvider；</li>
 *   <li>{@link #reload()} 在【模型配置】页保存时触发，解密重建并原子替换，无需重启（运行期热切换）；</li>
 *   <li>内置熔断器：连续失败达阈值进入 OPEN，后续请求直接下发<b>降级兜底帧</b>（普通搜索 / 报错），
 *       不再打供应商；冷却期后半开探测，恢复后自动回到 CLOSED（扣子积分续费即自动恢复）。</li>
 * </ul>
 * 供应商只干一件事，本管理器不感知业务逻辑。</p>
 */
@Service
public class ChatProviderManager {

    private final AiProperties aiProperties;
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

    public ChatProviderManager(AiProperties aiProperties, ModelConfigMapper modelConfigMapper,
                               CryptoService cryptoService, ObjectMapper objectMapper,
                               ToolExecutor toolExecutor, HttpClientFactory httpClientFactory) {
        this.aiProperties = aiProperties;
        this.modelConfigMapper = modelConfigMapper;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
        this.toolExecutor = toolExecutor;
        this.httpClientFactory = httpClientFactory;
    }

    /** 当前生效供应商（DB is_active 优先，回退 env 默认值） */
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

    private ChatProvider buildActive() {
        ModelConfig cfg = modelConfigMapper.selectOne(
                new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getActive, 1));
        if (cfg != null) {
            return build(cfg.getProvider(), cfg.getBaseUrl(), cfg.getModel(),
                    cryptoService.decrypt(cfg.getBotId()), cryptoService.decrypt(cfg.getApiKey()));
        }
        // 没有 is_active=1 的生效行：区分「已禁用」与「全新部署」，避免静默回退到空凭证 env 默认
        // （coze.pat 未配置 → 构造出 Bearer null 的坏 provider，要么秒 401、要么代理一抖就超时，体验极差）。
        long total = modelConfigMapper.selectCount(new LambdaQueryWrapper<>());
        if (total > 0) {
            // 库里已有配置但都没启用（用户点了「停用」）→ 明确报错，前端据此提示「请启用一个模型」，
            // 而不是发起一场必败的网络请求把用户卡在超时 spinner 上。
            throw new IllegalStateException("当前没有启用的 AI 模型，请在【模型配置】中启用一个模型");
        }
        // 全新部署（库里一行配置都没有）→ 回退 env 默认；但若 env 凭证也为空，给出清晰报错而非空 provider。
        String provider = aiProperties.getProvider();
        String baseUrl = switch (provider) {
            case "coze" -> aiProperties.getCoze().getBaseUrl();
            case "openai" -> aiProperties.getOpenai().getBaseUrl();
            case "deepseek" -> aiProperties.getOpenai().getBaseUrl();
            case "ollama" -> aiProperties.getOllama().getBaseUrl();
            default -> null;
        };
        String model = switch (provider) {
            case "coze" -> aiProperties.getCoze().getBotId();
            case "openai" -> aiProperties.getOpenai().getModel();
            case "deepseek" -> aiProperties.getOpenai().getModel();
            case "ollama" -> aiProperties.getOllama().getModel();
            default -> null;
        };
        String botId = switch (provider) {
            case "coze" -> aiProperties.getCoze().getBotId();
            default -> null;
        };
        String apiKey = switch (provider) {
            case "coze" -> aiProperties.getCoze().getPat();
            case "openai" -> aiProperties.getOpenai().getApiKey();
            case "deepseek" -> aiProperties.getOpenai().getApiKey();
            case "ollama" -> null;
            default -> null;
        };
        if (("coze".equals(provider) || "openai".equals(provider) || "deepseek".equals(provider))
                && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException(
                    "未启用任何模型配置，且 env 默认 " + provider + " 凭证未配置，无法连接 AI 网关；请在【模型配置】中启用一个模型");
        }
        return build(provider, baseUrl, model, botId, apiKey);
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
