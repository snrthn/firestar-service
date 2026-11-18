package com.firestar.ai.service;

import com.firestar.ai.entity.AiConversation;
import com.firestar.ai.entity.AiMessage;
import com.firestar.ai.entity.AiToolCall;
import com.firestar.ai.mapper.AiConversationMapper;
import com.firestar.ai.mapper.AiMessageMapper;
import com.firestar.ai.mapper.AiToolCallMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    public String getLatestDraft(Long convId, Long currentUserId) {
        if (convId == null) {
            return null;
        }
        // 归属校验（P1 越权修复）：草稿属于会话、会话属于用户。越权访问一律 404，
        // 与「会话不存在」不可区分，避免泄露他人会话中的发货草稿 JSON（含客户/项目/金额）。
        // 404 经 GlobalExceptionHandler.handleStatus 保留原 HTTP 状态码带出。
        AiConversation conv = convMapper.selectById(convId);
        if (conv == null || !Objects.equals(conv.getUserId(), currentUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或无权访问");
        }
        AiToolCall tc = toolCallMapper.selectOne(new LambdaQueryWrapper<AiToolCall>()
                .eq(AiToolCall::getConversationId, convId)
                .eq(AiToolCall::getToolName, "draft_shipment_create")
                .eq(AiToolCall::getIsError, 0)
                .orderByDesc(AiToolCall::getId)
                .last("LIMIT 1"));
        return tc != null ? tc.getResultJson() : null;
    }

    // ===== 历史会话（回放 / 续聊 / 删除）=====

    /** 续聊时回灌的历史消息条数上限（防超长上下文拖爆 token 预算） */
    public static final int HISTORY_LIMIT = 20;

    /** 归属校验（越权 404，与「会话不存在」不可区分，同 getLatestDraft 口径） */
    private AiConversation requireOwned(Long convId, Long currentUserId) {
        AiConversation conv = convMapper.selectById(convId);
        if (conv == null || !Objects.equals(conv.getUserId(), currentUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或无权访问");
        }
        return conv;
    }

    /**
     * 当前用户的历史会话分页（updatedAt 倒序，最近活跃在前）。
     */
    public IPage<AiConversation> listConversations(Long userId, int pageNum, int pageSize) {
        return convMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 50)),
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getUserId, userId)
                        .orderByDesc(AiConversation::getUpdatedAt)
                        .orderByDesc(AiConversation::getId));
    }

    /**
     * 会话消息列表（时间升序，含 user/assistant/tool 全部角色），归属校验后返回。
     * tool 行的 content 是工具结果 JSON，前端按 toolName 映射渲染业务卡。
     */
    public List<AiMessage> listMessages(Long convId, Long currentUserId) {
        requireOwned(convId, currentUserId);
        return msgMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, convId)
                .orderByAsc(AiMessage::getId));
    }

    /** 会话内 message_id → 工具名（tool 消息渲染业务卡的分类依据） */
    public Map<Long, String> toolNamesOf(Long convId) {
        Map<Long, String> out = new HashMap<>();
        for (AiToolCall tc : toolCallMapper.selectList(new LambdaQueryWrapper<AiToolCall>()
                .eq(AiToolCall::getConversationId, convId))) {
            out.put(tc.getMessageId(), tc.getToolName());
        }
        return out;
    }

    /** 删除会话及其消息 / 工具调用记录，归属校验后物理删除 */
    public void deleteConversation(Long convId, Long currentUserId) {
        requireOwned(convId, currentUserId);
        toolCallMapper.delete(new LambdaQueryWrapper<AiToolCall>().eq(AiToolCall::getConversationId, convId));
        msgMapper.delete(new LambdaQueryWrapper<AiMessage>().eq(AiMessage::getConversationId, convId));
        convMapper.deleteById(convId);
    }

    /**
     * 继续既有会话：归属校验 → 追加本轮 user 消息 → 会话重新置为进行中（结束后端统一落 status=2）。
     * 返回本轮之前的纯文本历史（user/assistant，时间升序，截取最近 HISTORY_LIMIT 条），
     * 供 Controller 回灌进供应商 messages 实现<b>多轮上下文</b>；tool 行不回灌（OpenAI 系需要
     * assistant(tool_calls) 与 tool 结果严格配对，直拼裸 tool 消息会被供应商 400 拒绝）。
     */
    public List<AiMessage> continueConversation(Long convId, Long userId, String userText) {
        AiConversation conv = requireOwned(convId, userId);
        List<AiMessage> all = msgMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, convId)
                .in(AiMessage::getRole, "user", "assistant")
                .orderByAsc(AiMessage::getId));
        int from = Math.max(0, all.size() - HISTORY_LIMIT);
        List<AiMessage> history = from > 0 ? all.subList(from, all.size()) : all;
        AiMessage m = new AiMessage();
        m.setConversationId(convId);
        m.setRole("user");
        m.setContent(userText);
        msgMapper.insert(m);
        conv.setStatus(1);
        convMapper.updateById(conv);
        return history;
    }
}
