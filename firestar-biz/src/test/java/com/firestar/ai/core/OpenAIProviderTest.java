package com.firestar.ai.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ChatMessage;
import com.firestar.ai.spi.TurnRequest;
import com.firestar.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAIProvider reasoning 通道回归测试：思考过程（reasoning_content）必须走独立 chunk，
 * 不得混进正文 token 流、不得拼进 r.content（否则思考串进落库正文与多轮回灌上下文）。
 */
class OpenAIProviderTest {

    private final ObjectMapper om = new ObjectMapper();

    private OpenAIProvider newProvider() {
        return new OpenAIProvider("http://mock", "k", "m", om, null, new HttpClientFactory());
    }

    private static ByteArrayInputStream sse(String raw) {
        return new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void reasoningGoesToItsOwnChannel_notMergedIntoContent() throws Exception {
        String stream = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想一下\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"再查数据\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"，火星\"}}]}",
                "data: [DONE]",
                "");

        List<ChatChunk> chunks = new ArrayList<>();
        OpenAIProvider.RoundResult r = newProvider().parseSse(sse(stream), chunks::add);

        assertEquals(4, chunks.size(), "思考与正文共 4 帧增量，逐帧下发");
        assertEquals("reasoning", chunks.get(0).type());
        assertEquals("先想一下", chunks.get(0).text());
        assertEquals("reasoning", chunks.get(1).type());
        assertEquals("再查数据", chunks.get(1).text());
        assertEquals("token", chunks.get(2).type());
        assertEquals("你好", chunks.get(2).text());
        assertEquals("token", chunks.get(3).type());
        assertEquals("，火星", chunks.get(3).text());
        // 正文聚合结果只含 content，思考过程不混入
        assertEquals("你好，火星", r.content);
    }

    @Test
    void pureReasoningStillYieldsEmptyContent() throws Exception {
        // 只思考不出正文（如续轮全 tool_calls）：正文聚合为空，前端靠独立 reasoning 帧渲染思考区
        String stream = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想一想\"}}]}",
                "data: [DONE]",
                "");

        List<ChatChunk> chunks = new ArrayList<>();
        OpenAIProvider.RoundResult r = newProvider().parseSse(sse(stream), chunks::add);

        assertEquals(1, chunks.size());
        assertEquals("reasoning", chunks.get(0).type());
        assertEquals("", r.content);
        assertTrue(r.toolCalls.isEmpty());
    }

    /** 编译期引用占位：TurnRequest/ChatMessage 的构造签名变更会被这里的 import 捕获 */
    @Test
    void turnRequestShapeUnchanged() {
        TurnRequest tr = new TurnRequest("ext", List.of(ChatMessage.of("user", "hi")), List.of(), null, "u", false);
        assertEquals("hi", tr.messages().get(0).content());
    }
}
