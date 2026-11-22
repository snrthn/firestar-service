package com.firestar.ai.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.spi.ChatChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CozeProvider SSE 分发逻辑单测：核心是「自言自语」回归防护——
 * 深度思考 Bot 的 reasoning_content 走独立 reasoning 通道，不混进正文；
 * verbose/function_call/follow_up 等编排消息一律丢弃。
 */
class CozeProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CozeProvider newProvider() {
        return new CozeProvider("https://api.coze.example", "bot-1", "pat", objectMapper, new HttpClientFactory());
    }

    private List<ChatChunk> dispatch(CozeProvider p, CozeProvider.StreamState state, String event, String... datas) throws Exception {
        List<ChatChunk> out = new ArrayList<>();
        p.dispatch(event, String.join("\n", datas), out::add, state);
        return out;
    }

    @Test
    @DisplayName("verbose/function_call/follow_up 等编排消息不下发（自言自语防护之一）")
    void nonAnswerMessageTypesAreDropped() throws Exception {
        CozeProvider p = newProvider();
        CozeProvider.StreamState state = new CozeProvider.StreamState();
        assertTrue(dispatch(p, state, "conversation.message.delta",
                "{\"type\":\"verbose\",\"content\":\"Plan: 先查用户表\",\"role\":\"assistant\"}").isEmpty());
        assertTrue(dispatch(p, state, "conversation.message.delta",
                "{\"type\":\"follow_up\",\"content\":\"你还可以问…\",\"role\":\"assistant\"}").isEmpty());
        assertTrue(dispatch(p, state, "conversation.message.delta",
                "{\"type\":\"function_call\",\"content\":\"{\\\"name\\\":\\\"search_files\\\"}\",\"role\":\"assistant\"}").isEmpty());
    }

    @Test
    @DisplayName("answer 类型的 content 增量按正文 token 下发")
    void answerContentStreamsAsToken() throws Exception {
        CozeProvider p = newProvider();
        CozeProvider.StreamState state = new CozeProvider.StreamState();
        List<ChatChunk> out = dispatch(p, state, "conversation.message.delta",
                "{\"type\":\"answer\",\"content\":\"请通过管理员重置密码。\",\"role\":\"assistant\"}");
        assertEquals(1, out.size());
        assertEquals("token", out.get(0).type());
        assertEquals("请通过管理员重置密码。", out.get(0).text());
        assertTrue(state.answerTokenEmitted);
    }

    @Test
    @DisplayName("深度思考增量走 reasoning 独立通道，不混进正文（自言自语根因修复）")
    void reasoningContentStreamsAsReasoningChunk() throws Exception {
        CozeProvider p = newProvider();
        CozeProvider.StreamState state = new CozeProvider.StreamState();
        // 思考阶段：content 空、reasoning_content 有内心戏
        List<ChatChunk> out = dispatch(p, state, "conversation.message.delta",
                "{\"type\":\"answer\",\"content\":\"\",\"reasoning_content\":\"用户问密码找回，我先梳理现有流程…\",\"role\":\"assistant\"}");
        assertEquals(1, out.size());
        assertEquals("reasoning", out.get(0).type());
        assertEquals("用户问密码找回，我先梳理现有流程…", out.get(0).text());
        // 思考不能标记为「正文已产出」：否则兜底逻辑永不触发
        assertTrue(!state.answerTokenEmitted);
    }

    @Test
    @DisplayName("先思考后回答的正常流：正文只含正式回答，结束时不重复下发思考")
    void thinkingThenAnswerDoesNotDuplicateReasoningIntoAnswer() throws Exception {
        CozeProvider p = newProvider();
        CozeProvider.StreamState state = new CozeProvider.StreamState();
        List<ChatChunk> all = new ArrayList<>();
        all.addAll(dispatch(p, state, "conversation.message.delta",
                "{\"type\":\"answer\",\"content\":\"\",\"reasoning_content\":\"内心戏…\",\"role\":\"assistant\"}"));
        all.addAll(dispatch(p, state, "conversation.message.delta",
                "{\"type\":\"answer\",\"content\":\"正式回答\",\"role\":\"assistant\"}"));
        all.addAll(dispatch(p, state, "conversation.chat.completed", "{\"code\":0}"));

        long reasoningCount = all.stream().filter(c -> "reasoning".equals(c.type())).count();
        long tokenCount = all.stream().filter(c -> "token".equals(c.type())).count();
        assertEquals(1, reasoningCount);
        assertEquals(1, tokenCount);
        assertEquals("正式回答", all.stream().filter(c -> "token".equals(c.type())).findFirst().orElseThrow().text());
        // 结束帧有且仅有一个 done
        assertEquals(1, all.stream().filter(c -> "done".equals(c.type())).count());
    }

    @Test
    @DisplayName("旧行为兜底：回复整体在 reasoning_content、content 恒空时，结束时回退为正文下发一次")
    void answerEntirelyInReasoningFallsBackToTokenOnCompletion() throws Exception {
        CozeProvider p = newProvider();
        CozeProvider.StreamState state = new CozeProvider.StreamState();
        List<ChatChunk> all = new ArrayList<>();
        all.addAll(dispatch(p, state, "conversation.message.delta",
                "{\"type\":\"answer\",\"content\":\"\",\"reasoning_content\":\"整体回复放在思考里\",\"role\":\"assistant\"}"));
        all.addAll(dispatch(p, state, "conversation.chat.completed", "{\"code\":0}"));

        // 思考帧 1 条 + 兜底正文 1 条 + done 1 条
        assertEquals(3, all.size());
        assertEquals("reasoning", all.get(0).type());
        assertEquals("token", all.get(1).type());
        assertEquals("整体回复放在思考里", all.get(1).text());
        assertEquals("done", all.get(2).type());
    }

    @Test
    @DisplayName("type 缺失的 delta 兼容按 answer 处理；空白 token 不下发")
    void missingTypeTreatedAsAnswerAndBlankTokensSuppressed() throws Exception {
        CozeProvider p = newProvider();
        CozeProvider.StreamState state = new CozeProvider.StreamState();
        // 首个 delta 常为纯空白：不下发
        assertTrue(dispatch(p, state, "conversation.message.delta",
                "{\"content\":\"\\n\",\"role\":\"assistant\"}").isEmpty());
        // 无 type 字段的正文照常下发
        List<ChatChunk> out = dispatch(p, state, "conversation.message.delta",
                "{\"content\":\"你好\",\"role\":\"assistant\"}");
        assertEquals(1, out.size());
        assertEquals("token", out.get(0).type());
    }
}
