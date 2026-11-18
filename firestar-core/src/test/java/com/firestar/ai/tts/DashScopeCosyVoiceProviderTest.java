package com.firestar.ai.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** V214：CosyVoice 协议 JSON 构造单测 —— 不连真实 WebSocket，验证事件结构与转义正确性。 */
class DashScopeCosyVoiceProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    // 用 null 依赖构造：本测试只调 buildXxx 纯函数，不触碰凭据/HTTP
    private final DashScopeCosyVoiceProvider provider =
            new DashScopeCosyVoiceProvider(null, null, mapper);

    @Test
    @DisplayName("run-task 结构：header.action/task_id/streaming + payload 参数齐全")
    void runTaskStructure() throws Exception {
        String taskId = "task-123";
        String json = provider.buildRunTask(taskId, "cosyvoice-v3-flash", "longanyang", "mp3", 22050);
        JsonNode root = mapper.readTree(json);

        assertEquals("run-task", root.at("/header/action").asText());
        assertEquals(taskId, root.at("/header/task_id").asText());
        assertEquals("duplex", root.at("/header/streaming").asText());
        assertEquals("audio", root.at("/payload/task_group").asText());
        assertEquals("tts", root.at("/payload/task").asText());
        assertEquals("SpeechSynthesizer", root.at("/payload/function").asText());
        assertEquals("cosyvoice-v3-flash", root.at("/payload/model").asText());
        assertEquals("longanyang", root.at("/payload/parameters/voice").asText());
        assertEquals("mp3", root.at("/payload/parameters/format").asText());
        assertEquals(22050, root.at("/payload/parameters/sample_rate").asInt());
        assertFalse(root.at("/payload/parameters/enable_ssml").asBoolean());
        assertTrue(root.at("/payload/input").isObject());
    }

    @Test
    @DisplayName("continue-task：同一 task_id，text 正确；特殊字符被引号安全转义")
    void continueTaskEscapesText() throws Exception {
        String text = "他说\"你好\"\n换行 & <tag>";
        String json = provider.buildContinueTask("task-123", text);
        JsonNode root = mapper.readTree(json);

        assertEquals("continue-task", root.at("/header/action").asText());
        assertEquals("task-123", root.at("/header/task_id").asText());
        assertEquals(text, root.at("/payload/input/text").asText(), "文本应原样保留（含引号/换行/尖括号）");
    }

    @Test
    @DisplayName("finish-task：同一 task_id，input 为空对象")
    void finishTask() throws Exception {
        JsonNode root = mapper.readTree(provider.buildFinishTask("task-123"));
        assertEquals("finish-task", root.at("/header/action").asText());
        assertEquals("task-123", root.at("/header/task_id").asText());
        assertTrue(root.at("/payload/input").isObject());
        assertEquals(0, root.at("/payload/input").size());
    }

    @Test
    @DisplayName("TtsResult contentType 映射：mp3/wav/opus/pcm/未知")
    void contentTypeMapping() {
        assertEquals("audio/mpeg", new TtsProvider.TtsResult(new byte[0], "mp3", 22050).contentType());
        assertEquals("audio/wav", new TtsProvider.TtsResult(new byte[0], "wav", 22050).contentType());
        assertEquals("audio/opus", new TtsProvider.TtsResult(new byte[0], "OPUS", 22050).contentType());
        assertEquals("audio/pcm", new TtsProvider.TtsResult(new byte[0], "pcm", 22050).contentType());
        assertEquals("audio/mpeg", new TtsProvider.TtsResult(new byte[0], null, 22050).contentType());
    }
}
