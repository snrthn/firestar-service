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
}
