package com.firestar.ai.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.core.HttpClientFactory;
import com.firestar.ai.service.ModelCredentialResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 百炼 CosyVoice 实时语音合成（V214，Java 17 原生 WebSocket，零第三方依赖）。
 *
 * <p>协议（run-task → task-started → continue-task → finish-task → task-finished）：
 * <ol>
 *   <li>建连：wss://.../api-ws/v1/inference，Header Authorization: Bearer &lt;key&gt;；</li>
 *   <li>发 run-task（model/voice/format/sample_rate），同一 task_id 贯穿全程；</li>
 *   <li>收 task-started → 发 continue-task（text）；文本可分段，本实现单次发送（受 maxChars 保护）；</li>
 *   <li>发 finish-task 强制合成缓存文本（不可省略）；</li>
 *   <li>二进制帧即音频流，追加到缓冲；收 task-finished 结束。</li>
 * </ol>
 * 凭据从 ModelCredentialResolver.resolve("tts") 读（V214 已预置 dashscope 行，
 * 密钥与 embedding/rerank 共用一把百炼 key 但配置行独立）。未启用时 available()=false，
 * Controller 给出清晰提示，不打断其它 AI 功能。</p>
 */
@Component
public class DashScopeCosyVoiceProvider implements TtsProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeCosyVoiceProvider.class);

    private static final String DEFAULT_BASE_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    private static final int DEFAULT_SAMPLE_RATE = 22050;
    private static final String DEFAULT_VOICE = "longanyang";
    private static final String DEFAULT_FORMAT = "mp3";
    private static final int MAX_CHARS = 2000; // 与 embedding maxChars 同口径，防止单次文本过长
    private static final long TIMEOUT_SECONDS = 60;

    private final ModelCredentialResolver credentials;
    private final HttpClientFactory clientFactory;
    private final ObjectMapper mapper;

    public DashScopeCosyVoiceProvider(ModelCredentialResolver credentials,
                                      HttpClientFactory clientFactory,
                                      ObjectMapper mapper) {
        this.credentials = credentials;
        this.clientFactory = clientFactory;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "dashscope";
    }

    @Override
    public boolean available() {
        try {
            ModelCredentialResolver.Credential c = credentials.resolve("tts");
            return c != null && c.apiKey() != null && !c.apiKey().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public TtsResult synthesize(String text, Map<String, Object> options) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("待合成文本不能为空");
        }
        String clipped = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        ModelCredentialResolver.Credential cred = credentials.resolve("tts");
        if (cred == null || cred.apiKey() == null || cred.apiKey().isBlank()) {
            throw new IllegalStateException("TTS 未配置：请在【模型配置】页启用一条 tts 类型的配置（dashscope）");
        }
        String baseUrl = (cred.baseUrl() == null || cred.baseUrl().isBlank())
                ? DEFAULT_BASE_URL : cred.baseUrl();
        String model = (cred.model() == null || cred.model().isBlank())
                ? "cosyvoice-v3-flash" : cred.model();
        String voice = optStr(options, "voice", cred.extraStr("voice", DEFAULT_VOICE));
        String format = optStr(options, "format", cred.extraStr("format", DEFAULT_FORMAT));
        int sampleRate = optInt(options, "sampleRate",
                parseExtraInt(cred.extraStr("sample_rate", cred.extraStr("sampleRate", null)), DEFAULT_SAMPLE_RATE));

        String taskId = UUID.randomUUID().toString();
        CompletableFuture<byte[]> resultFuture = new CompletableFuture<>();
        List<byte[]> audioChunks = new ArrayList<>();
        AtomicReference<WebSocket> wsRef = new AtomicReference<>();
        StringBuilder textBuffer = new StringBuilder();

        HttpClient client = clientFactory.newClient(Duration.ofSeconds(10));
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket ws) {
                wsRef.set(ws);
                ws.request(1);
                String runTask = buildRunTask(taskId, model, voice, format, sampleRate);
                log.debug("[TTS] task={} 发送 run-task model={} voice={} format={}", taskId, model, voice, format);
                ws.sendText(runTask, true);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                textBuffer.append(data);
                ws.request(1);
                if (!last) {
                    return null;
                }
                String msg = textBuffer.toString();
                textBuffer.setLength(0);
                handleEvent(ws, msg, taskId, clipped, resultFuture, audioChunks);
                return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                audioChunks.add(chunk);
                ws.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                if (!resultFuture.isDone()) {
                    resultFuture.completeExceptionally(
                            new IllegalStateException("TTS WebSocket 错误: " + error.getMessage(), error));
                }
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                if (!resultFuture.isDone()) {
                    resultFuture.completeExceptionally(
                            new IllegalStateException("TTS WebSocket 提前关闭: " + statusCode + " " + reason));
                }
                return null;
            }
        };

        try {
            CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + cred.apiKey())
                    .header("X-DashScope-DataInspection", "enable")
                    .buildAsync(URI.create(baseUrl), listener);
            wsFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // 建连（握手期鉴权失败会在此抛 401/403）

            byte[] audio = resultFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            WebSocket ws = wsRef.get();
            if (ws != null) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
            log.info("[TTS] 合成完成 task={} bytes={} format={} sampleRate={}",
                    taskId, audio.length, format, sampleRate);
            return new TtsResult(audio, format, sampleRate);
        } catch (TimeoutException e) {
            closeQuietly(wsRef.get());
            throw new IllegalStateException("TTS 合成超时（" + TIMEOUT_SECONDS + "s），task=" + taskId, e);
        } catch (Exception e) {
            closeQuietly(wsRef.get());
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("TTS 合成失败: " + e.getMessage(), e);
        }
    }

    /** 处理服务端文本事件：run-task 的确认 → 发文本；task-finished → 汇总音频完成。 */
    private void handleEvent(WebSocket ws, String msg, String taskId, String text,
                             CompletableFuture<byte[]> resultFuture, List<byte[]> audioChunks) {
        try {
            JsonNode root = mapper.readTree(msg);
            String event = root.path("header").path("event").asText("");
            switch (event) {
                case "task-started" -> {
                    log.debug("[TTS] task={} 收到 task-started，发送 continue-task + finish-task", taskId);
                    ws.sendText(buildContinueTask(taskId, text), true);
                    ws.sendText(buildFinishTask(taskId), true);
                }
                case "task-finished" -> {
                    int total = audioChunks.stream().mapToInt(a -> a.length).sum();
                    byte[] all = new byte[total];
                    int off = 0;
                    for (byte[] c : audioChunks) {
                        System.arraycopy(c, 0, all, off, c.length);
                        off += c.length;
                    }
                    resultFuture.complete(all);
                }
                case "task-failed" -> {
                    String err = root.path("header").path("error_message").asText("未知错误");
                    resultFuture.completeExceptionally(new IllegalStateException("TTS 任务失败: " + err));
                }
                default -> log.debug("[TTS] task={} 忽略事件: {}", taskId, event);
            }
        } catch (Exception e) {
            resultFuture.completeExceptionally(
                    new IllegalStateException("TTS 事件处理失败: " + e.getMessage(), e));
        }
    }

    /** 构造 run-task JSON（model/voice/format/sample_rate 等参数）。包可见便于单测。 */
    String buildRunTask(String taskId, String model, String voice, String format, int sampleRate) {
        try {
            var root = mapper.createObjectNode();
            var header = root.putObject("header");
            header.put("action", "run-task");
            header.put("task_id", taskId);
            header.put("streaming", "duplex");
            var payload = root.putObject("payload");
            payload.put("task_group", "audio");
            payload.put("task", "tts");
            payload.put("function", "SpeechSynthesizer");
            payload.put("model", model);
            var params = payload.putObject("parameters");
            params.put("text_type", "PlainText");
            params.put("voice", voice);
            params.put("format", format);
            params.put("sample_rate", sampleRate);
            params.put("volume", 50);
            params.put("rate", 1);
            params.put("pitch", 1);
            params.put("enable_ssml", false);
            payload.putObject("input");
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("构造 run-task 失败", e);
        }
    }

    /** 参数校验：文本里若含 JSON 特殊字符，用 ObjectMapper 构造而非字符串拼接（防注入/转义错误）。 */
    String buildContinueTask(String taskId, String text) {
        try {
            var root = mapper.createObjectNode();
            var header = root.putObject("header");
            header.put("action", "continue-task");
            header.put("task_id", taskId);
            header.put("streaming", "duplex");
            root.putObject("payload").putObject("input").put("text", text);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("构造 continue-task 失败", e);
        }
    }

    String buildFinishTask(String taskId) {
        try {
            var root = mapper.createObjectNode();
            var header = root.putObject("header");
            header.put("action", "finish-task");
            header.put("task_id", taskId);
            header.put("streaming", "duplex");
            root.putObject("payload").putObject("input");
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("构造 finish-task 失败", e);
        }
    }

    private static String optStr(Map<String, Object> options, String key, String def) {
        if (options == null || options.get(key) == null) {
            return def;
        }
        return String.valueOf(options.get(key));
    }

    private static int optInt(Map<String, Object> options, String key, int def) {
        if (options == null || options.get(key) == null) {
            return def;
        }
        Object v = options.get(key);
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }

    private static int parseExtraInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void closeQuietly(WebSocket ws) {
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "abort");
            } catch (Exception ignored) {
                // 关闭失败不影响主流程
            }
        }
    }
}
