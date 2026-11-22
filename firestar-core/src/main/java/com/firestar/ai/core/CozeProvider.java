package com.firestar.ai.core;

import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ChatMessage;
import com.firestar.ai.spi.ChatProvider;
import com.firestar.ai.spi.ToolCall;
import com.firestar.ai.spi.ToolExecutionPort;
import com.firestar.ai.spi.ToolResult;
import com.firestar.ai.spi.ToolSpec;
import com.firestar.ai.spi.TurnRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 扣子（Coze）供应商实现（设计 §4.2）。
 *
 * <p>只干一件事：吃 messages，吐 token 流。<b>端插件</b>链路：Bot 控制台把 FireStar 工具定义成
 * 「端插件」（客户端执行型），对话触发时扣子 SSE 推 {@code conversation.chat.requires_action}，
 * 本类在本地经 {@link ToolExecutionPort} 执行工具（与 OpenAI/DeepSeek 完全同一执行器与权限闸），
 * 再调 {@code /v3/chat/submit_tool_outputs} 提交结果，扣子据此生成最终文本。
 * 这样不需要公网可达的插件回调地址——所有请求都是我方主动出网。</p>
 *
 * <p>老的服务端插件回调链路见 AiPluginController，本期端插件与之并存、互不干扰。</p>
 */
public class CozeProvider implements ChatProvider {

    private static final Logger log = LoggerFactory.getLogger(CozeProvider.class);

    private final String baseUrl;
    private final String botId;
    private final String pat;
    private final ObjectMapper objectMapper;
    /** core 侧端口，biz 的 ToolExecutor 实现；null 时工具调用降级为错误结果 */
    private final ToolExecutionPort toolExecutor;
    private final HttpClient http;

    public CozeProvider(String baseUrl, String botId, String pat, ObjectMapper objectMapper,
                        ToolExecutionPort toolExecutor, HttpClientFactory httpClientFactory) {
        this.baseUrl = baseUrl;
        this.botId = botId;
        this.pat = pat;
        this.objectMapper = objectMapper;
        this.toolExecutor = toolExecutor;
        this.http = httpClientFactory.newClient(Duration.ofSeconds(10));
    }

    /** 无工具执行器的兼容构造（纯 SSE 解析单测/历史装配用），工具调用一律返回错误结果 */
    public CozeProvider(String baseUrl, String botId, String pat, ObjectMapper objectMapper,
                        HttpClientFactory httpClientFactory) {
        this(baseUrl, botId, pat, objectMapper, null, httpClientFactory);
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
        // 【端插件工具清单】注入当前用户可见工具名 + 参数 schema：扣子 Bot 的端插件定义在控制台，
        // 但模型需要知道何时调、怎么填参数；与 OpenAI 的 tools 数组等价（尽力而为的文本注入）。
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
        // 工具目录独立成条：不能挂在 systemInstr 里（系统指令为空时工具目录会一起消失）
        String catalog = toolCatalog(req.tools());
        if (!catalog.isBlank()) {
            additional.add(Map.of("role", "user", "content", catalog, "content_type", "text"));
        }
        additional.add(Map.of("role", "user", "content", userText, "content_type", "text"));
        String payload = objectMapper.writeValueAsString(Map.of(
                "bot_id", botIdValue(),
                "conversation_id", req.conversationId(),
                "user_id", "firestar-" + req.conversationId(),
                "stream", true,
                // 端插件 submit_tool_outputs 官方要求 auto_save_history=true，否则 5000 错误
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
        parseSse(resp.body(), new StreamState(), new ToolContext(req, onChunk));
    }

    /** 把当前用户可见工具拼成文本目录，随系统指令一起注入（模型据此知道端插件怎么调）。 */
    private String toolCatalog(List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【可用工具端插件】当且仅当需要系统数据时调用下列工具，"
                + "工具名和参数名必须完全一致；不需要系统数据时禁止调用：\n");
        for (ToolSpec s : tools) {
            sb.append("- ").append(s.name()).append(": ").append(s.description()).append('\n');
        }
        sb.append("调用端插件后请等待系统返回的工具结果，再基于结果回答，禁止编造。");
        return sb.toString();
    }

    /** 一次流式响应的聚合状态：正文是否已产出、思考文本累计、增量计数、待处理的端插件事件 */
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
        /** 端插件请求事件（流尾统一处理，避免过早 done） */
        String pendingAction;
        /**
         * 扣子 answer 首句过程独白过滤器（跨帧）。懒初始化：首次遇到 answer token 时
         * 用当前 onChunk 构造。只作用于扣子，其他 provider 不涉及。
         */
        AnswerPreambleFilter preambleFilter;
    }

    /** 一次对话的端插件执行上下文（跨多次 SSE 流共享：用户身份、工具白名单、步数预算） */
    static final class ToolContext {
        final TurnRequest req;
        final Consumer<ChatChunk> onChunk;
        final Set<String> seenCalls = ToolLoopPolicy.newSeen();
        int round;
        /** 步数耗尽/重复调用停滞：后续端插件请求只提交错误结果，不再继续生成 */
        boolean exhausted;

        ToolContext(TurnRequest req, Consumer<ChatChunk> onChunk) {
            this.req = req;
            this.onChunk = onChunk;
        }
    }

    /** 端插件工具调用解析结果 */
    record ToolInvocation(String id, String name, String arguments) {}

    /** 一次提交的输出项 */
    record ToolOutput(String toolCallId, String output) {}

    private void parseSse(InputStream in, StreamState state, ToolContext ctx) throws Exception {
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
                    handleEvent(currentEvent, data.toString(), state, ctx);
                    data.setLength(0);
                    currentEvent = "";
                }
            }
        }
        if (data.length() > 0) {
            handleEvent(currentEvent, data.toString(), state, ctx);
        }
        // 端插件请求：当前流到此为止，先执行工具并提交结果，再读后续流
        if (state.pendingAction != null) {
            String action = state.pendingAction;
            state.pendingAction = null;
            handleRequiresAction(action, state, ctx);
        }
    }

    private void handleEvent(String event, String dataBlock, StreamState state, ToolContext ctx) throws Exception {
        if ("conversation.chat.requires_action".equals(event)) {
            state.pendingAction = dataBlock;
            return;
        }
        // 端插件请求流里的 done 必须吞掉：此时对话并未真正结束，
        // 提交工具结果后扣子还会继续吐 token，提前 done 会让前端停止接收。
        Consumer<ChatChunk> sink = state.pendingAction != null
                ? chunk -> {
                    if (!"done".equals(chunk.type())) {
                        ctx.onChunk.accept(chunk);
                    }
                }
                : ctx.onChunk;
        dispatch(event, dataBlock, sink, state);
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
                    // 扣子专属：首句过程独白（「用户现在要查…我将调用…工具」）在 reasoning
                    // 通道已完整保留，正文这句是重复输出，过滤掉；其余正文原样透传。
                    // 用 onChunk 捕获当前 sink（端插件续流轮次里 sink 会吞中间 done），
                    // 保证过滤器输出仍走同一条下游链路。
                    if (state.preambleFilter == null) {
                        state.preambleFilter = new AnswerPreambleFilter(
                                safe -> onChunk.accept(ChatChunk.token(safe)));
                    }
                    state.preambleFilter.accept(text);
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
                // 首句独白过滤器 flush：把缓冲区残留正文吐出后再考虑 done，
                // 避免「正常短回复（无句末标点）被一直憋在缓冲里」导致正文区空白。
                if (state.preambleFilter != null) {
                    state.preambleFilter.finish();
                }
                // 旧行为兼容：部分深度思考 Bot 把正式回复整体放在 reasoning_content、
                // content 恒空（表现为「你好」之后只有前端硬编码的占位）。此时全程没有
                // 正文 token，把累计的思考文本回退为正文下发一次，避免正文区空白。
                if (!state.answerTokenEmitted && !state.fallbackUsed && state.reasoning.length() > 0) {
                    state.answerTokenEmitted = true;
                    state.fallbackUsed = true;
                    String fallbackText = state.reasoning.toString();
                    log.info("[AI][Coze] 全程无正文，reasoning 回退为正文（先过首句独白过滤）: chars={}, preview='{}'",
                            fallbackText.length(), preview(fallbackText));
                    // 2026-11-23 请求 f2f881c9 实锤：端插件第一轮流只有 reasoning（独白）没有
                    // answer delta，轮次完成事件触发本回退时独白直接进了正文——delta 链路上的
                    // 过滤器根本没参与。回退文本必须过同一个 AnswerPreambleFilter：
                    // 独白句在 reasoning 通道已完整保留，正文不重复输出。
                    if (state.preambleFilter == null) {
                        state.preambleFilter = new AnswerPreambleFilter(
                                safe -> onChunk.accept(ChatChunk.token(safe)));
                    }
                    state.preambleFilter.accept(fallbackText);
                    state.preambleFilter.finish();
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

    /**
     * 端插件请求：解析 tool_calls → 本地执行 → 提交结果 → 递归读扣子后续流。
     * 事件结构（官方 /v3/chat SSE）：
     * {"id":"chat_id","conversation_id":"...","required_action":{
     *   "type":"submit_tool_outputs","submit_tool_outputs":{"
     *     tool_calls":[{"id":"...","type":"function","function":{"name":"...","arguments":"{}"}}]}}}}
     */
    private void handleRequiresAction(String actionBlock, StreamState state, ToolContext ctx) throws Exception {
        JsonNode root = objectMapper.readTree(actionBlock.strip());
        JsonNode data = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
        String chatId = data.path("id").asText(null);
        String convId = data.path("conversation_id").asText(ctx.req.conversationId());
        List<ToolInvocation> calls = parseToolCalls(data);
        log.info("[AI][Coze] 端插件请求: chatId={}, round={}, calls={}", chatId, ctx.round, calls.size());
        if (chatId == null || chatId.isBlank()) {
            log.warn("[AI][Coze] requires_action 缺少 chat id，忽略本次工具请求");
            ctx.onChunk.accept(ChatChunk.done());
            return;
        }
        List<ToolOutput> outputs = new ArrayList<>();
        if (ctx.exhausted || !ToolLoopPolicy.shouldOfferTools(ctx.round, true)) {
            // tool_call_id 必须对应真实调用，扣子才能把结果匹配回本次工具请求
            if (calls.isEmpty()) {
                outputs.add(new ToolOutput("", "{\"error\":\"工具调用轮数已达上限，请基于已有结果总结回答\"}"));
            } else {
                for (ToolInvocation inv : calls) {
                    outputs.add(new ToolOutput(inv.id(), "{\"error\":\"工具调用轮数已达上限，请基于已有结果总结回答\"}"));
                }
            }
            // 非流式提交只为结束本轮对话，不再递归读流
            submitToolOutputs(convId, chatId, false, outputs);
            ctx.onChunk.accept(ChatChunk.done());
            return;
        }
        List<String> sigs = new ArrayList<>();
        for (ToolInvocation inv : calls) {
            sigs.add(ToolLoopPolicy.signature(inv.name(), inv.arguments()));
        }
        for (ToolInvocation inv : calls) {
            String args = inv.arguments() == null || inv.arguments().isBlank() ? "{}" : inv.arguments();
            ToolCall call = new ToolCall(inv.id(), inv.name(), args);
            ToolResult tr;
            if (!toolAllowed(ctx.req, inv.name())) {
                tr = new ToolResult(inv.id(), inv.name(), "{\"error\":\"工具不在当前用户可见清单，已拒绝执行\"}", true);
            } else if (toolExecutor == null) {
                tr = new ToolResult(inv.id(), inv.name(), "{\"error\":\"工具执行器未装配\"}", true);
            } else {
                long start = System.currentTimeMillis();
                tr = toolExecutor.execute(call, ctx.req.username(), ctx.req.isAdmin());
                ChatChunk audit = ToolAuditFrame.of(start, call, tr, objectMapper);
                if (audit != null) {
                    ctx.onChunk.accept(audit);
                }
                if (inv.name() != null && inv.name().startsWith("draft_")) {
                    try {
                        String draft = objectMapper.writeValueAsString(Map.of(
                                "toolName", inv.name(), "argsJson", args,
                                "resultJson", tr == null ? "" : tr.contentJson()));
                        ctx.onChunk.accept(ChatChunk.draft(draft));
                    } catch (Exception ignored) {
                        // 草稿事件失败不阻塞主流程
                    }
                }
            }
            outputs.add(new ToolOutput(inv.id(), tr == null ? "{}" : tr.contentJson()));
        }
        // 全是重复调用 → 下一轮不再执行工具，逼模型基于已有结果总结（同 OpenAI 护栏）
        boolean fresh = ToolLoopPolicy.hasFreshCalls(sigs, ctx.seenCalls);
        ToolLoopPolicy.markSeen(sigs, ctx.seenCalls);
        ctx.exhausted = !fresh && !calls.isEmpty();
        if (outputs.isEmpty()) {
            outputs.add(new ToolOutput("", "{\"error\":\"未解析到可执行的工具调用\"}"));
        }
        HttpResponse<InputStream> next = submitToolOutputs(convId, chatId, true, outputs);
        String contentType = next.headers().firstValue("Content-Type").orElse("");
        if (next.statusCode() != 200 || !contentType.contains("text/event-stream")) {
            String body = new String(next.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("扣子提交工具结果失败 HTTP " + next.statusCode()
                    + " Content-Type=" + contentType + ": " + body);
        }
        ctx.round++;
        // 递归读提交后的新流：token 走同一 onChunk，最终 done 由新流发出
        parseSse(next.body(), state, ctx);
    }

    /** 从 requires_action 的 data 节点解析工具调用（包内可见，供单测直接投喂官方样例） */
    static List<ToolInvocation> parseToolCalls(JsonNode data) {
        List<ToolInvocation> out = new ArrayList<>();
        JsonNode arr = data.path("required_action").path("submit_tool_outputs").path("tool_calls");
        if (!arr.isArray()) {
            arr = data.path("tool_calls"); // 兼容简化结构
        }
        if (!arr.isArray()) {
            return out;
        }
        for (JsonNode tc : arr) {
            String id = tc.path("id").asText(null);
            String name = tc.path("function").path("name").asText(null);
            String args = tc.path("function").path("arguments").asText("{}");
            if (name == null || name.isBlank()) {
                continue;
            }
            out.add(new ToolInvocation(id, name, args));
        }
        return out;
    }

    private boolean toolAllowed(TurnRequest req, String name) {
        if (name == null || req.tools() == null) {
            return false;
        }
        for (ToolSpec spec : req.tools()) {
            if (name.equals(spec.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 端插件 submit 输出统一成单字段 text 契约（包内可见供单测）。
     * 扣子按输出参数 schema 取值，schema 配错的工具结果会被当空丢弃——这是此前「只有组织架构能查」的根因。
     * 统一包成 {"text": ...} 后，扣子侧所有工具的输出参数只需配同一个 text:string 字段，
     * DTO 字段增删改不再波及扣子 schema。已是该结构的（query_orgs）原样透传，避免双重包装。
     */
    static String wrapToolOutputForCoze(String raw, ObjectMapper mapper) {
        String s = raw == null ? "" : raw;
        try {
            JsonNode node = mapper.readTree(s);
            if (node.isObject() && node.size() == 1 && node.has("text")) {
                return s; // 已是单字段 text 契约，原样透传
            }
        } catch (Exception ignored) {
            // 非 JSON 纯文本：走下方包装
        }
        try {
            return mapper.writeValueAsString(java.util.Map.of("text", s));
        } catch (Exception e) {
            return "{\"text\":\"\"}";
        }
    }

    /** 构造 POST /v3/chat/submit_tool_outputs 请求（包内可见，供单测断言 payload） */
    HttpRequest buildSubmitRequest(String convId, String chatId, boolean stream, List<ToolOutput> outputs) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolOutput o : outputs) {
            items.add(Map.of("tool_call_id", o.toolCallId() == null ? "" : o.toolCallId(),
                    "output", wrapToolOutputForCoze(o.output(), objectMapper)));
        }
        String body = objectMapper.writeValueAsString(Map.of("stream", stream, "tool_outputs", items));
        String url = baseUrl.replaceAll("/$", "") + "/v3/chat/submit_tool_outputs?conversation_id="
                + URLEncoder.encode(convId == null ? "" : convId, StandardCharsets.UTF_8)
                + "&chat_id=" + URLEncoder.encode(chatId == null ? "" : chatId, StandardCharsets.UTF_8);
        return HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", "Bearer " + pat)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private HttpResponse<InputStream> submitToolOutputs(String convId, String chatId, boolean stream,
                                                        List<ToolOutput> outputs) throws Exception {
        HttpRequest req = buildSubmitRequest(convId, chatId, stream, outputs);
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        log.info("[AI][Coze] 提交工具结果: chatId={}, outputs={}, status={}", chatId, outputs.size(), resp.statusCode());
        return resp;
    }

    /** 日志预览截断：只留首尾各 60 字符，避免思考长文本刷屏 */
    private static String preview(String s) {
        if (s.length() <= 120) {
            return s;
        }
        return s.substring(0, 60) + "..." + s.substring(s.length() - 60);
    }
}
