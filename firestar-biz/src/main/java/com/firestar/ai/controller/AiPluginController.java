package com.firestar.ai.controller;

import com.firestar.ai.core.CozePluginVerifier;
import com.firestar.ai.service.AiChatService;
import com.firestar.ai.spi.ToolCall;
import com.firestar.ai.spi.ToolResult;
import com.firestar.ai.tool.ToolExecutor;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.service.UserRoleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 扣子插件回调端点（设计 §4.3 / §6）。
 *
 * <p>扣子在会话中需要工具时会异步回调本端点。<b>本端点靠 HMAC 签名鉴权、不靠 JWT</b>，
 * 已在 SecurityConfig 加入白名单（permitAll）。流程：
 * ① 校验 X-Coze-Signature（HMAC-SHA256，防伪造与重放）；
 * ② 用回调体里的 conversation_id 反查本地 ai_conversations，得到会话所有者 userId；
 * ③ 以<b>该用户身份</b>（username + isAdmin）执行白名单内的工具（绝不采用扣子 user_id）；
 * ④ 结果经 EgressMasker 脱敏后回给扣子，完成工具调用闭环。</p>
 */
@RestController
@RequestMapping("/ai")
public class AiPluginController {

    private final CozePluginVerifier verifier;
    private final AiChatService chatService;
    private final UserMapper userMapper;
    private final UserRoleService userRoleService;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    public AiPluginController(CozePluginVerifier verifier, AiChatService chatService,
                             UserMapper userMapper, UserRoleService userRoleService,
                             ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.chatService = chatService;
        this.userMapper = userMapper;
        this.userRoleService = userRoleService;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/coze/plugin")
    public ResponseEntity<String> handle(
            @RequestHeader(value = "X-Coze-Signature", required = false) String signature,
            @RequestHeader(value = "X-Coze-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Coze-Nonce", required = false) String nonce,
            @RequestBody String body) {

        if (!verifier.verify(signature, timestamp, nonce, body)) {
            return ResponseEntity.status(401)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":401,\"msg\":\"invalid signature\"}");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String conversationId = node.has("conversation_id") ? node.get("conversation_id").asText() : null;
            String toolName = node.has("name") ? node.get("name").asText()
                    : (node.has("tool_id") ? node.get("tool_id").asText() : null);
            JsonNode params = node.has("parameters") ? node.get("parameters")
                    : node.has("arguments") ? node.get("arguments") : objectMapper.createObjectNode();
            String argsJson = params.isObject() ? objectMapper.writeValueAsString(params)
                    : (params.isTextual() ? params.asText() : "{}");

            com.firestar.ai.entity.AiConversation conv = chatService.findByExternalSessionId(conversationId);
            if (conv == null) {
                return ok("{\"code\":0,\"msg\":\"\",\"data\":{\"error\":\"会话不存在\"}}");
            }
            Long userId = conv.getUserId();
            User u = userMapper.selectById(userId);
            String username = u == null ? "" : u.getUsername();
            boolean isAdmin = u != null && userRoleService.findRoleCodesByUserId(userId).contains("ROLE_ADMIN");

            ToolCall call = new ToolCall("coze-" + conversationId + "-" + System.nanoTime(), toolName, argsJson);
            long start = System.currentTimeMillis();
            ToolResult result = toolExecutor.execute(call, username, isAdmin);
            chatService.recordToolCall(conv.getId(), toolName, argsJson, result.contentJson(),
                    0, result.isError(), System.currentTimeMillis() - start);

            String data = result.isError()
                    ? "{\"error\":\"" + sanitize(result.contentJson()) + "\"}"
                    : result.contentJson();
            return ok("{\"code\":0,\"msg\":\"\",\"data\":" + data + "}");
        } catch (Exception e) {
            return ok("{\"code\":0,\"msg\":\"\",\"data\":{\"error\":\"" + sanitize(e.getMessage()) + "\"}}");
        }
    }

    private ResponseEntity<String> ok(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private String sanitize(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }
}
