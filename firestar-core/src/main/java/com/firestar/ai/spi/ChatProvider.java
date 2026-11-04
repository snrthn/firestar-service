package com.firestar.ai.spi;

import java.util.function.Consumer;

/**
 * 供应商无关的核心契约（适配层命门，设计 §4.1）。
 *
 * <p>供应商只准干一件事：吃 messages + tools，吐 token / tool_call 流；
 * 会话、工具执行、权限、脱敏、审计全部留在 FireStar 后端，且供应商无关。
 * 切换供应商 = 换一个实现类，业务/前端/网关/会话/审计零改动。</p>
 */
public interface ChatProvider {

    /** 供应商标识：coze | openai | deepseek | ollama */
    String name();

    /**
     * 建立外部会话并返回其 id（如扣子 conversation_id）。
     * 该 id 持久化到 ai_conversations.external_session_id，
     * 供插件回调（扣子调我方 ToolExecutor）时反查当前登录用户。
     *
     * @param userRef 仅用于供应商侧会话连续性（如扣子 user_id），<b>不可信</b>，不用于数据授权
     */
    String createSession(String userRef) throws Exception;

    /**
     * 流式推送一轮对话。
     *
     * <p><b>契约（重要，切换供应商时务必遵守）：</b>token 必须<b>逐块</b>经 {@code onChunk} 回调下发，
     * 不得把整段响应缓冲完毕后一次性回调（否则前端会表现为「最后一刻一把吐出」，与供应商无关）。
     * 每个增量片段（content / reasoning_content 等）一旦从供应商流中解析出来，应立即
     * {@code onChunk.accept(ChatChunk.token(...))}；空片段（仅空白）应过滤，避免气泡首行空出。</p>
     *
     * <p>抛异常 = 供应商不可用（由 {@code ChatProviderManager} 触发熔断 / 降级）。</p>
     */
    void streamChat(TurnRequest req, Consumer<ChatChunk> onChunk) throws Exception;
}
