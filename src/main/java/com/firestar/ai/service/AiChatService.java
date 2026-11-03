package com.firestar.ai.service;

import com.firestar.ai.entity.AiConversation;
import com.firestar.ai.entity.AiMessage;
import com.firestar.ai.entity.AiToolCall;
import com.firestar.ai.mapper.AiConversationMapper;
import com.firestar.ai.mapper.AiMessageMapper;
import com.firestar.ai.mapper.AiToolCallMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/**
 * AI 会话编排与落库（设计 §8）。会话 / 消息 / 工具调用全落自有库，不依赖扣子 7 天日志。
 * 工具调用记录由插件回调端点（AiPluginController）调用 {@link #recordToolCall}。
 */
@Service
public class AiChatService {

    private final AiConversationMapper convMapper;
    private final AiMessageMapper msgMapper;
    private final AiToolCallMapper toolCallMapper;

    public AiChatService(AiConversationMapper convMapper, AiMessageMapper msgMapper,
                         AiToolCallMapper toolCallMapper) {
        this.convMapper = convMapper;
        this.msgMapper = msgMapper;
        this.toolCallMapper = toolCallMapper;
    }

    /** 开会话 + 落首条 user 消息，返回会话 id */
    public Long startConversation(Long userId, String username, String provider, String firstUserText) {
        AiConversation c = new AiConversation();
        c.setUserId(userId);
        c.setProvider(provider);
        c.setStatus(1);
        c.setTitle(firstUserText == null ? "" : (firstUserText.length() > 50 ? firstUserText.substring(0, 50) : firstUserText));
        convMapper.insert(c);
        AiMessage m = new AiMessage();
        m.setConversationId(c.getId());
        m.setRole("user");
        m.setContent(firstUserText);
        msgMapper.insert(m);
        return c.getId();
    }

    public void setExternalSessionId(Long convId, String extId) {
        AiConversation c = convMapper.selectById(convId);
        if (c != null) {
            c.setExternalSessionId(extId);
            convMapper.updateById(c);
        }
    }

    /** 落 assistant 消息（流式结束后调用） */
    public void appendAssistantMessage(Long convId, String content, String modelVersion, long latencyMs) {
        AiMessage m = new AiMessage();
        m.setConversationId(convId);
        m.setRole("assistant");
        m.setContent(content);
        m.setModelVersion(modelVersion);
        m.setLatencyMs((int) latencyMs);
        msgMapper.insert(m);
    }

    /** 落工具调用审计（插件回调路径调用）。返回写入的 message id（供 tool_call 关联） */
    public Long recordToolCall(Long convId, String toolName, String argsJson,
                               String resultJson, int rowsReturned, boolean isError, long latencyMs) {
        AiMessage m = new AiMessage();
        m.setConversationId(convId);
        m.setRole("tool");
        m.setContent(resultJson);
        msgMapper.insert(m);

        AiToolCall tc = new AiToolCall();
        tc.setConversationId(convId);
        tc.setMessageId(m.getId());
        tc.setToolName(toolName);
        tc.setArgumentsJson(argsJson);
        tc.setResultJson(resultJson);
        tc.setRowsReturned(rowsReturned);
        tc.setIsError(isError ? 1 : 0);
        tc.setLatencyMs((int) latencyMs);
        toolCallMapper.insert(tc);
        return m.getId();
    }

    public void endConversation(Long convId) {
        AiConversation c = convMapper.selectById(convId);
        if (c != null) {
            c.setStatus(2);
            convMapper.updateById(c);
        }
    }

    /** 按外部会话 id（扣子 conversation_id）反查本地会话 */
    public AiConversation findByExternalSessionId(String externalSessionId) {
        if (externalSessionId == null) {
            return null;
        }
        return convMapper.selectOne(new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getExternalSessionId, externalSessionId));
    }

    /**
     * 取会话最新的草稿预览结果（Coze 模式下 SSE 流不经过草稿结果，前端需在流结束后按会话拉取）。
     * 仅返回最近一次非错误的 {@code draft_shipment_create} 工具结果 JSON；无则返回 null。
     */
    public String getLatestDraft(Long convId) {
        if (convId == null) {
            return null;
        }
        AiToolCall tc = toolCallMapper.selectOne(new LambdaQueryWrapper<AiToolCall>()
                .eq(AiToolCall::getConversationId, convId)
                .eq(AiToolCall::getToolName, "draft_shipment_create")
                .eq(AiToolCall::getIsError, 0)
                .orderByDesc(AiToolCall::getId)
                .last("LIMIT 1"));
        return tc != null ? tc.getResultJson() : null;
    }
}
