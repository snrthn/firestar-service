package com.firestar.ai.core;

import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ChatMessage;
import com.firestar.ai.spi.ChatProvider;
import com.firestar.ai.spi.TurnRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 扣子（Coze）供应商实现（设计 §4.2）。
 *
 * <p>只干一件事：吃 messages，吐 token 流。工具调用由扣子侧「自定义插件」触发——
 * 扣子在会话中需要工具时会<b>异步回调</b>我方 {@code /ai/coze/plugin} 端点（见 AiPluginController），
 * 该端点经 HMAC 校验后以当前登录用户身份执行 {@code ToolExecutor} 并把结果回给扣子，
 * 扣子继续生成最终文本。因此本类的 streamChat 只负责转发扣子 /v3/chat 的 SSE 文本增量。</p>
 */
public class CozeProvider implements ChatProvider {

    private static final Logger log = LoggerFactory.getLogger(CozeProvider.class);

    private final String baseUrl;
    private final String botId;
    private final String pat;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public CozeProvider(String baseUrl, String botId, String pat, ObjectMapper objectMapper,
                        HttpClientFactory httpClientFactory) {
        this.baseUrl = baseUrl;
        this.botId = botId;
        this.pat = pat;
        this.objectMapper = objectMapper;
        this.http = httpClientFactory.newClient(Duration.ofSeconds(10));
    }

    @Override
    public String name() {
        return "coze";
    }

    /**
     * 扣子 API 的 bot_id 字段以「字符串」形式传递（官方 Go SDK 的 {@code BotID} 即 string 类型，
     * 且实测字符串可成功建会话，传数字反被网关以 400 拒）。故此处原样返回 trim 后的字符串，
     * 不做数字转换。null 时返回 null。
     */
    private Object botIdValue() {
        if (botId == null) {
            return null;
        }
        return botId.trim();
    }

    /** 建立扣子会话，返回 conversation_id（持久化到 ai_conversations.external_session_id） */
    @Override
    public String createSession(String userRef) throws Exception {
        String url = baseUrl.replaceAll("/$", "") + "/v1/conversation/create";
        String body = objectMapper.writeValueAsString(Map.of("bot_id", botIdValue(), "user_id", userRef));
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", "Bearer " + pat)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode node = objectMapper.readTree(resp.body());
        if (node.has("data") && node.get("data").has("id")) {
            return node.get("data").get("id").asText();
        }
        throw new IllegalStateException("扣子创建会话失败: " + resp.body());
    }

    @Override
    public void streamChat(TurnRequest req, Consumer<ChatChunk> onChunk) throws Exception {
        String url = baseUrl.replaceAll("/$", "") + "/v3/chat";
        String systemInstr = req.messages().stream()
                .filter(m -> "system".equals(m.role()))
                .map(ChatMessage::content)
                .reduce((a, b) -> b)
                .orElse(null);
        String userText = req.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .reduce((a, b) -> b)
                .map(ChatMessage::content)
                .orElse("");
        // 扣子 chat API 不接受外部 system 覆盖（Bot 提示词固化在控制台），
        // 故将 FireStar 的系统指令以「用户角色前导指令」注入，尽力而为地约束工具调用行为。
        // 策略文本来自 FireStar（AiProperties），切到 OpenAI/DeepSeek 时改以真正 system 消息执行，行为一致。
        // 【输出格式规范】单独成段：实测扣子侧模型输出「无换行的内联伪 Markdown」（如 "###1.标题正文连写"、
        // "-列表项不换行"，679 字 0 换行 9 个 ##），前端 Markdown 渲染器无从下手，整段糊成一坨。
        // 真正的人设级约束仍应配置在扣子控制台 Bot 的人设里（优先级高于用户消息注入），此处是尽力而为的一层。
        List<Object> additional = new ArrayList<>();
        if (systemInstr != null && !systemInstr.isBlank()) {
            additional.add(Map.of("role", "user", "content",
                    "[系统指令-请严格遵守且在回复中不要向用户提及] " + systemInstr
                            + "\n\n【输出格式规范】回答必须使用标准 Markdown 格式，严格遵守换行规则："
                            + "1. 标题（#/##/###）、列表项（- 或 1.）必须独占一行，不得与正文连写在同一行；"
                            + "2. 标记符号后必须紧跟一个空格（如 ### 标题、- 列表项、1. 条目）；"
                            + "3. 段落之间用空行分隔；"
                            + "4. 禁止输出没有换行的伪 Markdown。",
                    "content_type", "text"));
        }
        additional.add(Map.of("role", "user", "content", userText, "content_type", "text"));
        String payload = objectMapper.writeValueAsString(Map.of(
                "bot_id", botIdValue(),
                "conversation_id", req.conversationId(),
                "user_id", "firestar-" + req.conversationId(),
                "stream", true,
                "auto_save_history", true,
                "additional_messages", additional
        ));
        HttpRequest hr = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", "Bearer " + pat)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> resp = http.send(hr, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("扣子 chat 失败 HTTP " + resp.statusCode() + ": " + err);
        }
        // 扣子在某些情况下返回 200 + 一段普通 JSON 错误体（非 SSE），例如
        // bot 未发布到 API 渠道（code=4015）。若不拦截，parseSse 会静默吞掉，
        // 前端只看到空的 event:done 而看不到真实错误。故此处按 Content-Type 分流处理。
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        if (!contentType.contains("text/event-stream")) {
            String body = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(body);
            if (node.isObject() && node.has("code") && node.get("code").asInt(0) != 0) {
                String msg = node.has("msg") ? node.get("msg").asText() : body;
                throw new IllegalStateException("扣子返回错误 code=" + node.get("code").asText() + ": " + msg);
            }
            throw new IllegalStateException("扣子返回非 SSE 响应(Content-Type=" + contentType + "): " + body);
        }
        parseSse(resp.body(), onChunk);
    }

    /** 一次流式响应的聚合状态：正文是否已产出、思考文本累计、增量计数（供结束诊断日志） */
    static final class StreamState {
        final StringBuilder reasoning = new StringBuilder();
        boolean answerTokenEmitted;
        /** type=answer 的正文增量条数 */
        int answerDeltas;
        /** reasoning_content 增量条数 */
        int reasoningDeltas;
        /** 被丢弃的非 answer 编排消息条数（verbose/function_call/follow_up 等） */
        int droppedDeltas;
        /** 结束时是否触发了「思考文本回退为正文」兜底 */
        boolean fallbackUsed;
    }

    private void parseSse(InputStream in, Consumer<ChatChunk> onChunk) throws Exception {
        StreamState state = new StreamState();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String currentEvent = "";
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("event:")) {
                // Coze 为标准 SSE：event 与 data 分两行，需单独记录当前事件名
                currentEvent = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).strip()).append("\n");
            } else if (line.isBlank()) {
                if (data.length() > 0) {
                    dispatch(currentEvent, data.toString(), onChunk, state);
                    data.setLength(0);
                    currentEvent = "";
                }
            }
        }
        if (data.length() > 0) {
            dispatch(currentEvent, data.toString(), onChunk, state);
        }
    }

    /** 包内可见：供单测直接投喂 SSE 事件验证分发逻辑，不真正走网络 */
    void dispatch(String event, String dataBlock, Consumer<ChatChunk> onChunk, StreamState state) throws Exception {
        for (String raw : dataBlock.split("\n")) {
            if (raw.isBlank()) {
                continue;
            }
            JsonNode node = objectMapper.readTree(raw);
            if (!node.isObject()) {
                continue;
            }
            if ("conversation.message.delta".equals(event) || "conversation.chat.delta".equals(event)) {
                // Coze /v3/chat 的 delta 中 content / reasoning_content 为「顶层字段」
                // （非嵌套在 data 内），这里直接读取。
                String text = "";
                if (node.has("content") && node.get("content").isTextual()) {
                    text = node.get("content").asText();
                }
                // 消息 type 过滤：answer 是面向用户的正式回复；verbose（编排节点说明）、
                // function_call、tool_output、follow_up（建议追问）都是 Bot 内部信息，
                // 混进正文气泡就是用户看到的「自言自语」。type 缺失时按 answer 处理（兼容旧行为）。
                String msgType = node.has("type") && node.get("type").isTextual()
                        ? node.get("type").asText() : "answer";
                if (!"answer".equals(msgType)) {
                    state.droppedDeltas++;
                    continue;
                }
                if (!text.isBlank()) {
                    state.answerTokenEmitted = true;
                    state.answerDeltas++;
                    onChunk.accept(ChatChunk.token(text));
                    continue;
                }
                // 深度思考增量：走独立 reasoning 通道（前端渲染进「已深度思考」折叠区、
                // 落库到 reasoning_text，与 DeepSeek 行为一致）。旧版在这里把思考当正文
                // token 下发，深度思考 Bot「先吐思考再吐回答」，正文气泡里全是内心戏——
                // 这正是切换扣子后回答「自言自语」的直接来源。
                if (node.has("reasoning_content") && node.get("reasoning_content").isTextual()) {
                    String reasoning = node.get("reasoning_content").asText();
                    if (!reasoning.isBlank()) {
                        state.reasoning.append(reasoning);
                        state.reasoningDeltas++;
                        onChunk.accept(ChatChunk.reasoning(reasoning));
                    }
                }
            } else if ("conversation.message.completed".equals(event)
                    || "conversation.chat.completed".equals(event) || "done".equals(event)) {
                // 旧行为兼容：部分深度思考 Bot 把正式回复整体放在 reasoning_content、
                // content 恒空（表现为「你好」之后只有前端硬编码的占位）。此时全程没有
                // 正文 token，把累计的思考文本回退为正文下发一次，避免正文区空白。
                if (!state.answerTokenEmitted && !state.fallbackUsed && state.reasoning.length() > 0) {
                    state.answerTokenEmitted = true;
                    state.fallbackUsed = true;
                    onChunk.accept(ChatChunk.token(state.reasoning.toString()));
                }
                // 诊断日志：只在流的最终 done 事件打一次（completed 事件每条 message 各有一个）。
                // 出问题时可直接对照——
                // answerDeltas=0 + reasoningDeltas>0 ⇒ 正文兜底回退（自言自语复现）；
                // answerDeltas=0 + reasoningDeltas=0 ⇒ Bot 没产出任何面向用户的内容（多为插件/编排问题）。
                if ("done".equals(event)) {
                    log.info("[AI][Coze] 流结束: answerDeltas={}, reasoningDeltas={}, dropped={}, "
                                    + "fallback={}, reasoningChars={}, reasoningPreview='{}'",
                            state.answerDeltas, state.reasoningDeltas, state.droppedDeltas,
                            state.fallbackUsed, state.reasoning.length(),
                            preview(state.reasoning.toString()));
                }
                onChunk.accept(ChatChunk.done());
            } else if ("error".equals(event)
                    || (node.has("code") && node.get("code").asInt(0) != 0)) {
                String msg = node.has("msg") ? node.get("msg").asText() : node.toString();
                throw new IllegalStateException("扣子返回错误: " + msg);
            }
        }
    }

    /** 日志预览截断：只留首尾各 60 字符，避免思考长文本刷屏 */
    private static String preview(String s) {
        if (s.length() <= 120) {
            return s;
        }
        return s.substring(0, 60) + "..." + s.substring(s.length() - 60);
    }
}
