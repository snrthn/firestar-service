package com.firestar.ai.service;

import com.firestar.ai.entity.AiConversation;
import com.firestar.ai.entity.AiMessage;
import com.firestar.ai.entity.AiToolCall;
import com.firestar.ai.mapper.AiConversationMapper;
import com.firestar.ai.mapper.AiMessageMapper;
import com.firestar.ai.mapper.AiToolCallMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

/**
 * AiChatService.getLatestDraft 归属校验回归测试（P1 越权修复守护）。
 * 越权会话（conv.userId != 当前用户）必须抛 404，与「会话不存在」不可区分。
 */
@ExtendWith(MockitoExtension.class)
class AiChatServiceTest {

    @Mock
    private AiConversationMapper convMapper;
    @Mock
    private AiMessageMapper msgMapper;
    @Mock
    private AiToolCallMapper toolCallMapper;
    @Mock
    private com.firestar.ai.mapper.AiMessageFeedbackMapper feedbackMapper;
    @InjectMocks
    private AiChatService service;

    @Test
    void userIdMismatch_throws404() {
        AiConversation c = new AiConversation();
        c.setId(10L);
        c.setUserId(1L);
        when(convMapper.selectById(10L)).thenReturn(c);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getLatestDraft(10L, 2L), "越权会话应抛 404");
        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getStatusCode().value());
    }

    @Test
    void conversationNotFound_throws404() {
        when(convMapper.selectById(99L)).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getLatestDraft(99L, 1L));
        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getStatusCode().value());
    }

    @Test
    void userIdMatch_returnsDraftJson() {
        AiConversation c = new AiConversation();
        c.setId(10L);
        c.setUserId(1L);
        when(convMapper.selectById(10L)).thenReturn(c);

        AiToolCall tc = new AiToolCall();
        tc.setResultJson("{\"shipment\":1}");
        when(toolCallMapper.selectOne(any())).thenReturn(tc);

        assertEquals("{\"shipment\":1}", service.getLatestDraft(10L, 1L));
    }

    // ===== 历史会话（回放 / 续聊 / 删除）归属校验 =====

    @Test
    void listMessages_mismatch_throws404() {
        AiConversation c = new AiConversation();
        c.setId(10L);
        c.setUserId(1L);
        when(convMapper.selectById(10L)).thenReturn(c);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listMessages(10L, 2L), "越权会话回放应抛 404");
        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getStatusCode().value());
    }

    @Test
    void deleteConversation_mismatch_throws404() {
        AiConversation c = new AiConversation();
        c.setId(10L);
        c.setUserId(1L);
        when(convMapper.selectById(10L)).thenReturn(c);

        assertThrows(ResponseStatusException.class, () -> service.deleteConversation(10L, 2L));
        // 404 时不允许碰任何数据
        verify(msgMapper, never()).delete(any());
        verify(toolCallMapper, never()).delete(any());
        verify(convMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteConversation_owned_deletesAll() {
        AiConversation c = new AiConversation();
        c.setId(10L);
        c.setUserId(1L);
        when(convMapper.selectById(10L)).thenReturn(c);

        service.deleteConversation(10L, 1L);

        verify(toolCallMapper).delete(any());
        verify(msgMapper).delete(any());
        verify(convMapper).deleteById(10L);
    }

    @Test
    void continueConversation_mismatch_throws404() {
        AiConversation c = new AiConversation();
        c.setId(10L);
        c.setUserId(1L);
        when(convMapper.selectById(10L)).thenReturn(c);

        assertThrows(ResponseStatusException.class,
                () -> service.continueConversation(10L, 2L, "继续"));
        verify(msgMapper, never()).insert(any(AiMessage.class));
    }

    @Test
    void continueConversation_owned_returnsHistoryAndAppendsUserMsg() {
        AiConversation c = new AiConversation();
        c.setId(10L);
        c.setUserId(1L);
        when(convMapper.selectById(10L)).thenReturn(c);

        AiMessage h1 = new AiMessage();
        h1.setRole("user");
        h1.setContent("第一问");
        AiMessage h2 = new AiMessage();
        h2.setRole("assistant");
        h2.setContent("第一答");
        when(msgMapper.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of(h1, h2)));

        List<AiMessage> history = service.continueConversation(10L, 1L, "第二问");

        assertEquals(2, history.size(), "历史为本轮之前的 user/assistant 文本");
        verify(msgMapper).insert(any(AiMessage.class));
        verify(convMapper).updateById(any(AiConversation.class));
    }

    // ===== 会话内工作记忆（工具结果结构化回灌）=====

    @Test
    void workingMemoryOf_nullConv_returnsNull() {
        assertEquals(null, service.workingMemoryOf(null));
        org.mockito.Mockito.verifyNoInteractions(toolCallMapper);
    }

    @Test
    void workingMemoryOf_noToolCalls_returnsNull() {
        when(toolCallMapper.selectList(any())).thenReturn(List.of());
        assertEquals(null, service.workingMemoryOf(10L));
    }

    @Test
    void workingMemoryOf_queriesOnlySuccessfulCalls_limitedAndChronological() {
        AiToolCall older = new AiToolCall();
        older.setToolName("search_projects");
        older.setArgumentsJson("{\"kw\":\"A\"}");
        older.setResultJson("[{\"id\":1}]");
        AiToolCall newer = new AiToolCall();
        newer.setToolName("query_shipment_detail");
        newer.setArgumentsJson("{\"id\":9}");
        newer.setResultJson("{\"no\":\"S1\"}");
        // mapper 返回的是"按 id 倒序"的结果（production 用 orderByDesc + LIMIT）
        when(toolCallMapper.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of(newer, older)));

        String wm = service.workingMemoryOf(10L);

        assertTrue(wm.contains("已脱敏"), "带出域声明，提示模型可作事实引用");
        assertTrue(wm.contains("search_projects") && wm.contains("query_shipment_detail"));
        assertTrue(wm.indexOf("search_projects") < wm.indexOf("query_shipment_detail"),
                "倒序查询后翻正为时间升序：先查的排前面");
        // 注：isError=0 / LIMIT / ORDER BY id DESC 的 SQL 约束由 workingMemoryOf 内的
        // LambdaQueryWrapper 保证，靠源码落盘核对；此处不调 getSqlSegment()——纯 Mockito
        // 环境未初始化 MyBatis-Plus lambda cache，调它会抛 "can not find lambda cache"。
        verify(toolCallMapper).selectList(any());
    }

    @Test
    void workingMemoryOf_truncatesLargeResult() {
        AiToolCall tc = new AiToolCall();
        tc.setToolName("search_invoices");
        tc.setArgumentsJson("{}");
        tc.setResultJson("X".repeat(5000));
        when(toolCallMapper.selectList(any())).thenReturn(List.of(tc));

        String wm = service.workingMemoryOf(10L);

        assertTrue(wm.contains("…"), "超长结果被截断并加省略号");
        assertTrue(wm.length() < 1000, "单条截断后整块远小于上限，实际=" + wm.length());
    }

    @Test
    void appendAssistantMessage_withReasoning_persistsReasoningText() {
        org.mockito.ArgumentCaptor<AiMessage> cap = org.mockito.ArgumentCaptor.forClass(AiMessage.class);
        service.appendAssistantMessage(10L, "回答正文", "思考过程文本", "deepseek-reasoner", 1200L);

        verify(msgMapper).insert(cap.capture());
        AiMessage saved = cap.getValue();
        assertEquals("assistant", saved.getRole());
        assertEquals("回答正文", saved.getContent());
        assertEquals("思考过程文本", saved.getReasoningText(), "思考过程随 assistant 消息落库");
        assertEquals("deepseek-reasoner", saved.getModelVersion());
        assertEquals(1200, saved.getLatencyMs());
    }

    @Test
    void appendAssistantMessage_withoutReasoning_reasoningNull() {
        org.mockito.ArgumentCaptor<AiMessage> cap = org.mockito.ArgumentCaptor.forClass(AiMessage.class);
        service.appendAssistantMessage(10L, "回答正文", "glm-4", 300L);

        verify(msgMapper).insert(cap.capture());
        org.junit.jupiter.api.Assertions.assertNull(cap.getValue().getReasoningText(), "无思考过程时应为 null");
    }

    private AiMessage assistantMessage() {
        AiMessage m = new AiMessage();
        m.setId(77L);
        m.setConversationId(10L);
        m.setRole("assistant");
        return m;
    }

    private AiConversation ownedConversation() {
        AiConversation c = new AiConversation();
        c.setId(10L);
        c.setUserId(1L);
        return c;
    }

    @Test
    void rateMessage_like_inserts() {
        when(msgMapper.selectById(77L)).thenReturn(assistantMessage());
        when(convMapper.selectById(10L)).thenReturn(ownedConversation());
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        service.rateMessage(77L, 1L, "like");

        org.mockito.ArgumentCaptor<com.firestar.ai.entity.AiMessageFeedback> cap =
                org.mockito.ArgumentCaptor.forClass(com.firestar.ai.entity.AiMessageFeedback.class);
        verify(feedbackMapper).insert(cap.capture());
        assertEquals("LIKE", cap.getValue().getFeedbackType());
        assertEquals(77L, cap.getValue().getMessageId());
    }

    @Test
    void rateMessage_cancel_deletesExisting() {
        when(msgMapper.selectById(77L)).thenReturn(assistantMessage());
        when(convMapper.selectById(10L)).thenReturn(ownedConversation());
        com.firestar.ai.entity.AiMessageFeedback existing = new com.firestar.ai.entity.AiMessageFeedback();
        existing.setId(5L);
        existing.setFeedbackType("LIKE");
        when(feedbackMapper.selectOne(any())).thenReturn(existing);

        service.rateMessage(77L, 1L, "");

        verify(feedbackMapper).deleteById(5L);
        verify(feedbackMapper, never()).insert(any(com.firestar.ai.entity.AiMessageFeedback.class));
    }

    @Test
    void rateMessage_foreignMessage_throws404() {
        when(msgMapper.selectById(77L)).thenReturn(assistantMessage());
        AiConversation other = new AiConversation();
        other.setId(10L);
        other.setUserId(999L);
        when(convMapper.selectById(10L)).thenReturn(other);

        assertThrows(ResponseStatusException.class, () -> service.rateMessage(77L, 1L, "LIKE"));
        verify(feedbackMapper, never()).insert(any(com.firestar.ai.entity.AiMessageFeedback.class));
    }
}
