package com.firestar.ai.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.ai.dto.ConversationMessageVO;
import com.firestar.ai.dto.ConversationVO;
import com.firestar.ai.entity.AiMessage;
import com.firestar.ai.service.AiChatService;
import com.firestar.common.DataScopeHelper;
import com.firestar.common.Result;
import com.firestar.common.RequirePerm;
import com.firestar.mapper.UserMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 会话查询端点（设计 §8 / 历史会话）。
 *
 * <p>挂 {@code /ai} 下，与 {@link AiPluginController} 同源；鉴权走全局 JWT（非 coze 白名单）。
 * 提供：① 当前用户会话分页列表（「最近会话」面板）② 会话消息回放（含工具名，前端据此渲染
 * draft_shipment_create 等业务卡）③ 会话删除 ④ 按会话拉取最新草稿（Coze 模式流后补拉）。
 * 所有端点一律先做归属校验：越权会话 404，与「会话不存在」不可区分。</p>
 */
@RestController
@RequestMapping("/ai")
public class AiConversationController {

    private final AiChatService chatService;
    private final UserMapper userMapper;

    public AiConversationController(AiChatService chatService, UserMapper userMapper) {
        this.chatService = chatService;
        this.userMapper = userMapper;
    }

    /** 当前用户的历史会话分页列表（updatedAt 倒序） */
    @RequirePerm("ai:assistant:use")
    @GetMapping("/conversations")
    public Result<IPage<ConversationVO>> conversations(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        Long currentUserId = DataScopeHelper.currentUserId(userMapper);
        IPage<ConversationVO> page = chatService.listConversations(currentUserId, pageNum, pageSize)
                .convert(c -> new ConversationVO(c.getId(), c.getTitle(), c.getProvider(),
                        c.getStatus(), c.getCreatedAt(), c.getUpdatedAt()));
        return Result.success(page);
    }

    /** 会话消息回放（时间升序，含 tool 行 + 工具名，前端渲染业务卡） */
    @RequirePerm("ai:assistant:use")
    @GetMapping("/conversation/{convId}/messages")
    public Result<List<ConversationMessageVO>> messages(@PathVariable Long convId) {
        Long currentUserId = DataScopeHelper.currentUserId(userMapper);
        List<AiMessage> msgs = chatService.listMessages(convId, currentUserId);
        Map<Long, String> toolNames = chatService.toolNamesOf(convId);
        return Result.success(msgs.stream().map(m -> new ConversationMessageVO(
                m.getId(), m.getRole(), m.getContent(), m.getReasoningText(),
                toolNames.get(m.getId()),
                m.getModelVersion(), m.getLatencyMs(), m.getCreatedAt())).toList());
    }

    /** 删除会话（连同消息与工具调用记录），物理删除 */
    @RequirePerm("ai:assistant:use")
    @DeleteMapping("/conversation/{convId}")
    public Result<Void> deleteConversation(@PathVariable Long convId) {
        Long currentUserId = DataScopeHelper.currentUserId(userMapper);
        chatService.deleteConversation(convId, currentUserId);
        return Result.success();
    }

    /** 取会话最新的发货单草稿预览 JSON（无则返回 null） */
    @RequirePerm("ai:assistant:use")
    @GetMapping("/conversation/{convId}/latest-draft")
    public Result<String> latestDraft(@PathVariable Long convId) {
        // 归属校验：当前登录用户 id 传入 service，越权会话返回 null（与无草稿无差别）
        Long currentUserId = DataScopeHelper.currentUserId(userMapper);
        return Result.success(chatService.getLatestDraft(convId, currentUserId));
    }
}
