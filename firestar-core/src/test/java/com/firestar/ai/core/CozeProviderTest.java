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
    @DisplayName("扣子 answer 首句过程独白被过滤，reasoning 里的同一句保留，正式回答不受影响")
    void answerPreambleDroppedButReasoningKept() throws Exception {
        CozeProvider p = newProvider();
        CozeProvider.StreamState state = new CozeProvider.StreamState();
        List<ChatChunk> out = new ArrayList<>();
        // 思考通道先输出过程独白（保留）
        p.dispatch("conversation.message.delta",
                "{\"type\":\"answer\",\"content\":\"\",\"reasoning_content\":\"用户现在需要查询研发中心下面的部门数量，我将直接调用组织架构查询工具。\",\"role\":\"assistant\"}",
                out::add, state);
        // 正文首句重复了这句独白 + 正式回答（独白应被过滤）
        p.dispatch("conversation.message.delta",
                "{\"type\":\"answer\",\"content\":\"用户现在需要查询研发中心下面的部门数量，我将直接调用组织架构查询工具完成本次查询。\",\"role\":\"assistant\"}",
                out::add, state);
        p.dispatch("conversation.message.delta",
                "{\"type\":\"answer\",\"content\":\"研发中心下属共有2个部门：产品部、技术部。\",\"role\":\"assistant\"}",
                out::add, state);
        // 流结束 flush
        p.dispatch("conversation.chat.completed", "{\"code\":0}", out::add, state);

        List<ChatChunk> tokens = out.stream().filter(c -> "token".equals(c.type())).toList();
        assertEquals(1, tokens.size(), "正文只剩正式回答一句");
        assertEquals("研发中心下属共有2个部门：产品部、技术部。", tokens.get(0).text());
        List<ChatChunk> reasoning = out.stream().filter(c -> "reasoning".equals(c.type())).toList();
        assertEquals(1, reasoning.size(), "reasoning 里的过程独白保留");
        assertTrue(reasoning.get(0).text().contains("我将直接调用组织架构查询工具"));
    }

    @Test
    @DisplayName("端插件首轮流只有 reasoning：fallback 回退正文时独白被过滤（请求 f2f881c9 根因修复）")
    void fallbackReasoningToAnswerFiltersPreamble() throws Exception {
        CozeProvider p = newProvider();
        CozeProvider.StreamState state = new CozeProvider.StreamState();
        List<ChatChunk> out = new ArrayList<>();
        // 第一轮流（工具调用前）：只有 reasoning 独白，没有任何 answer delta
        p.dispatch("conversation.message.delta",
                "{\"type\":\"answer\",\"content\":\"\",\"reasoning_content\":\"用户现在问当前待开票金额是多少，为获取待开票金额，我将调用开票统计工具，无需额外参数即可获取本月相关数据。\",\"role\":\"assistant\"}",
                out::add, state);
        // 第一轮流结束事件触发 reasoning→正文回退：纯独白被整段摘除，不再回退（等续流正文）
        p.dispatch("conversation.chat.completed", "{\"code\":0}", out::add, state);
        List<ChatChunk> tokensAfterFallback = out.stream().filter(c -> "token".equals(c.type())).toList();
        assertTrue(tokensAfterFallback.isEmpty(),
                "回退文本只有独白一句，过滤后正文应为空，实际: " + tokensAfterFallback);
        // 2026-11-23 会话18 实锤后行为变化：reasoning 纯独白 → 摘除后为空 → 不回退、不置位，
        // 等续流的正式回答（旧逻辑无脑置位会导致正文区先吐空标记）
        assertTrue(!state.answerTokenEmitted, "纯独白 reasoning 摘除后为空，不应回退");
        assertTrue(state.fallbackUsed);
        // 端插件续流：正式回答正常透传
        p.dispatch("conversation.message.delta",
                "{\"type\":\"answer\",\"content\":\"当前待开票金额为 618.0元。\",\"role\":\"assistant\"}",
                out::add, state);
        List<ChatChunk> tokens = out.stream().filter(c -> "token".equals(c.type())).toList();
        assertEquals(1, tokens.size());
        assertEquals("当前待开票金额为 618.0元。", tokens.get(0).text());
        // reasoning 里的独白保留（用户展开「已深度思考」能看到）
        List<ChatChunk> reasoning = out.stream().filter(c -> "reasoning".equals(c.type())).toList();
        assertEquals(1, reasoning.size());
        assertTrue(reasoning.get(0).text().contains("我将调用开票统计工具"));
    }

    @Test
    @DisplayName("reasoning 多句独白连发（「正在为你查询…」无「工具」字样）：fallback 摘除全部独白句，实质句保留回退（会话18/请求 f2ac5f45 实锤形态）")
    void fallbackMultiSentenceNarrationStripped() throws Exception {
        CozeProvider p = newProvider();
        CozeProvider.StreamState state = new CozeProvider.StreamState();
        List<ChatChunk> out = new ArrayList<>();
        // 生产实测形态：多句独白，以「正在」开头、句内无「工具」二字（原词表拦不住）
        p.dispatch("conversation.message.delta",
                "{\"type\":\"answer\",\"content\":\"\",\"reasoning_content\":\"正在为你查询用户「马占山」的系统账号信息，获取对应的用户ID以便后续统计其关联的项目和回款数据。正在为你统计马占山名下的全部项目清单及数量。这是回退应保留的实质内容。\",\"role\":\"assistant\"}",
                out::add, state);
        p.dispatch("conversation.chat.completed", "{\"code\":0}", out::add, state);
        List<ChatChunk> tokens = out.stream().filter(c -> "token".equals(c.type())).toList();
        assertEquals(1, tokens.size(), "多句独白摘除后实质句应作为回退正文");
        assertEquals("这是回退应保留的实质内容。", tokens.get(0).text());
        assertTrue(state.answerTokenEmitted);
        assertTrue(state.fallbackUsed);
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
