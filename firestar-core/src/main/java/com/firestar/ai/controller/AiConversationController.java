package com.firestar.ai.controller;

import com.firestar.ai.service.AiChatService;
import com.firestar.common.DataScopeHelper;
import com.firestar.common.Result;
import com.firestar.common.RequirePerm;
import com.firestar.mapper.UserMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 会话查询端点（设计 §8 / 阶段二）。
 *
 * <p>挂 {@code /ai} 下，与 {@link AiPluginController} 同源；鉴权走全局 JWT（非 coze 白名单）。
 * 当前提供「按会话拉取最新草稿」，供 Coze 模式在前端流结束后渲染「待确认表单」卡片
 * （Coze 的工具调用走异步插件回调，SSE 流本身不经过草稿结果，只能事后拉取）。</p>
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

    /** 取会话最新的发货单草稿预览 JSON（无则返回 null） */
    @RequirePerm("ai:assistant:use")
    @GetMapping("/conversation/{convId}/latest-draft")
    public Result<String> latestDraft(@PathVariable Long convId) {
        // 归属校验：当前登录用户 id 传入 service，越权会话返回 null（与无草稿无差别）
        Long currentUserId = DataScopeHelper.currentUserId(userMapper);
        return Result.success(chatService.getLatestDraft(convId, currentUserId));
    }
}
