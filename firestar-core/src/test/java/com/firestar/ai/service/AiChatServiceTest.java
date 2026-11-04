package com.firestar.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.ai.entity.AiConversation;
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
import static org.mockito.Mockito.when;

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
        when(toolCallMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tc);

        assertEquals("{\"shipment\":1}", service.getLatestDraft(10L, 1L));
    }
}
