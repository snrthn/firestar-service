package com.firestar.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.ai.core.ChatProviderManager;
import com.firestar.ai.core.ToolAuditFrame;
import com.firestar.ai.dto.AiChatRequest;
import com.firestar.ai.config.AiProperties;
import com.firestar.ai.service.AiChatService;
import com.firestar.ai.service.PromptConfigService;
import com.firestar.ai.spi.ChatMessage;
import com.firestar.ai.spi.TurnRequest;
import com.firestar.ai.tool.ToolRegistry;
import com.firestar.common.RequirePerm;
import com.firestar.common.SecurityContextUtils;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * AI 对话端点（设计 §10）。
 *
 * <p>前端用 {@code fetch + ReadableStream} 消费本端点（带 Authorization），
 * 不使用 EventSource（带不了请求头，那正是 /sse/ 把 token 放 query 的根因）。
 * 本端点经 JWT 过滤器鉴权（SecurityConfig 已保证认证），返回 SseEmitter 流式转发供应商 token。</p>
 *
 * <p><b>路径约定</b>：本端点挂在 {@code /sse/ai/chat/stream}（后端实际路径，不含 /api），
 * 复用全站 SSE 前缀 /sse/ —— nginx 的 {@code location /api/sse/}（proxy_pass .../sse/ + buffering/gzip/cache off）
 * 会自动把 {@code /api/sse/ai/chat/stream} 透传为后端 {@code /sse/ai/chat/stream}，
 * <b>无需为 AI 流新增任何 nginx 配置</b>。
 * 注意 /sse/** 整体在 SecurityConfig 是 permitAll（鉴权下沉到 SseEndpointSupport），
 * 但本控制器走的是 JWT 过滤器鉴权、不做内部 token 解析，故 SecurityConfig 已对
 * {@code /sse/ai/**} 单独声明 authenticated()，插在 /sse/** permitAll 之前，避免被误放行。</p>
 *
 * <p>流协议事件：{@code token}(文本增量) / {@code done}(结束) / {@code error}(错误) / {@code fallback}(降级兜底帧)。
 * 降级时下发 fallback 帧，前端据此把抽屉切到普通搜索 / 报错形态（见 §4.3）。</p>
 */
@RestController
@RequestMapping("/sse/ai")
public class AiChatController {

    private final ChatProviderManager providerManager;
    private final AiChatService chatService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final AiProperties aiProperties;
    private final PromptConfigService promptConfigService;

    public AiChatController(ChatProviderManager providerManager, AiChatService chatService,
                           UserMapper userMapper, ObjectMapper objectMapper,
                           ToolRegistry toolRegistry, AiProperties aiProperties,
                           PromptConfigService promptConfigService) {
        this.providerManager = providerManager;
        this.chatService = chatService;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.aiProperties = aiProperties;
        this.promptConfigService = promptConfigService;
    }

    @RequirePerm("ai:assistant:use")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody AiChatRequest req, HttpServletRequest request,
                                 HttpServletResponse response) {
        // SSE 必须即时推送：禁用各级缓冲，否则浏览器会攒批到连接结束才一次性刷出
        // （现象：最后一刻「一把吐出所有内容」）。同时关缓存、标注非缓冲供反向代理识别。
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        try {
            response.setBufferSize(0);
        } catch (Exception ignored) {
            // 某些容器不支持 0 缓冲，忽略；SseEmitter.send 本身也会 flush
        }
        String username = SecurityContextUtils.currentUsername();
        boolean isAdmin = SecurityContextUtils.isAdmin();
        Long userId = resolveUserId(username);

        SseEmitter emitter = new SseEmitter(300_000L);

        CompletableFuture.runAsync(() -> {
            long start = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();
            // convId 用数组盒包裹：既是 effectively final（供 chunk lambda 捕获），又能在 catch 中读取/赋值
            final Long[] convIdBox = {null};
            String provider = "unknown";
            try {
                // provider / convId 在此惰性解析：buildActive() 在无生效模型时会抛清晰异常，
                // 放进 runAsync 内才能经 fail() 以 SSE error 帧下发给前端，而非返回 500。
                provider = providerManager.activeProvider().name();
                convIdBox[0] = chatService.startConversation(userId, username, provider, req.message());
                // 流起始元数据帧：携带 conversationId，供前端事后按会话拉取草稿（Coze 模式 SSE 流不经过草稿结果）
                try {
                    emitter.send(SseEmitter.event().name("meta")
                            .data(objectMapper.writeValueAsString(Map.of("conversationId", convIdBox[0]))));
                } catch (Exception ignored) {
                    // 客户端未连接等：best-effort，不影响后续流程
                }
                // 建立外部会话（如扣子 conversation_id），持久化以供插件回调反查用户
                String extId = providerManager.createSession("u" + userId);
                chatService.setExternalSessionId(convIdBox[0], extId);

                // 供应商无关的系统指令（工具调用纪律）作为首条 system 消息注入：
                // 优先取【提示词管理】中 is_active=1 的生效方案（运行期可热切换）；
                // 无生效方案时回退 AiProperties 默认常量（向后兼容）。
                // OpenAI/DeepSeek/Ollama 系原样遵守；Coze 由 CozeProvider 尽力而为地前导注入。
                List<ChatMessage> messages = new ArrayList<>();
                String systemInstruction = promptConfigService.getActiveContent();
                if (systemInstruction == null) {
                    systemInstruction = aiProperties.getChatSystemInstruction();
                }
                messages.add(ChatMessage.of("system", systemInstruction));
                messages.add(ChatMessage.of("user", req.message()));
                TurnRequest tr = new TurnRequest(extId,
                        messages,
                        toolRegistry.all(), null, username, isAdmin);
                providerManager.streamWithFallback(tr, chunk -> {
                    try {
                        switch (chunk.type()) {
                            case "token" -> {
                                sb.append(chunk.text());
                                emitter.send(SseEmitter.event().name("token").data(chunk.text()));
                            }
                            case "fallback" -> emitter.send(SseEmitter.event().name("fallback")
                                    .data(objectMapper.writeValueAsString(
                                            Map.of("mode", chunk.text(), "reason", chunk.data()))));
                            case "draft" -> {
                                // 草稿预览帧：仅透传给前端渲染「待确认表单」卡片。
                                // 审计统一由 tool_call 帧负责（N-13），此处不再 recordToolCall 避免双写。
                                emitter.send(SseEmitter.event().name("draft").data(chunk.data()));
                            }
                            case "tool_call" -> {
                                // N-13：直连供应商（OpenAI/DeepSeek/Anthropic）的工具调用审计帧。
                                // provider 只有外部 session id，本地 convId 在这里才有，故统一在此落库。
                                // 下游不感知此帧（不 emitter.send），纯服务端审计。
                                try {
                                    ToolAuditFrame.Data audit = ToolAuditFrame.parse(chunk, objectMapper);
                                    if (audit != null) {
                                        chatService.recordToolCall(convIdBox[0], audit.toolName(),
                                                audit.argsJson(), audit.resultJson(), 0,
                                                audit.isError(), audit.latencyMs());
                                    }
                                } catch (Exception ignored) {
                                    // 审计写失败不阻塞主链路（与 draft 旧行为一致）
                                }
                            }
                            case "error" -> emitter.send(SseEmitter.event().name("error").data(chunk.text()));
                            case "done" -> { /* 结束统一在下方 */ }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(""));
                chatService.appendAssistantMessage(convIdBox[0], sb.toString(), provider, System.currentTimeMillis() - start);
                chatService.endConversation(convIdBox[0]);
                emitter.complete();
            } catch (RuntimeException re) {
                re.printStackTrace();
                // 包装自 onChunk 内的 IO 异常
                if (re.getCause() instanceof Exception cause) {
                    fail(emitter, convIdBox[0], describe(cause));
                } else {
                    fail(emitter, convIdBox[0], describe(re));
                }
            } catch (Exception e) {
                e.printStackTrace();
                fail(emitter, convIdBox[0], describe(e));
            }
        });
        return emitter;
    }

    private void fail(SseEmitter emitter, Long convId, String msg) {
        // 先把错误以 SSE error 事件下发给前端（真实错误文案，如「扣子创建会话失败: ...」）。
        // 注意：必须用 emitter.complete() 干净收尾，不能用 completeWithError(...) —— 后者会让 Tomcat
        // 对异步请求发起 ERROR 重分发，重分发时 SecurityContext 已丢，/sse/ai/** 被当匿名
        // 触发 AuthorizationFilter 抛 AccessDeniedException，把真实错误盖成 403（已实测）。
        // 错误已通过 error 事件送达，clean complete 即可避免二次安全拦截。
        try {
            emitter.send(SseEmitter.event().name("error").data(sanitize(msg)));
        } catch (Exception ignored) {
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
        chatService.endConversation(convId);
    }

    private Long resolveUserId(String username) {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).select(User::getId));
        return u == null ? null : u.getId();
    }

    private String sanitize(String s) {
        return s == null ? "未知错误" : s.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }

    /** 把异常转成可读文案：优先「类名: message」，message 为 null 时也保留类名，避免前端只看到「未知错误」谜语 */
    private String describe(Exception e) {
        if (e == null) {
            return "未知错误";
        }
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}
