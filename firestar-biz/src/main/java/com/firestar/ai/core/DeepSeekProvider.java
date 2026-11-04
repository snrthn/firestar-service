package com.firestar.ai.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firestar.ai.tool.ToolExecutor;

/**
 * DeepSeek 供应商（OpenAI 兼容协议，设计 §4.2）。
 *
 * <p>DeepSeek 的 /chat/completions 与 OpenAI 完全兼容（Bearer 鉴权、tool_calls 函数调用语义一致），
 * 故本类直接继承 {@link OpenAIProvider} 复用其 SSE 解析与工具调用本地聚合循环，仅覆盖 {@link #name()}
 * 以在会话记录 / 审计中准确标识为 deepseek（而非被归并到 openai）。</p>
 *
 * <p>前端【模型配置】页填法：供应商选 DeepSeek，Base URL 填 {@code https://api.deepseek.com}
 * （或 {@code https://api.deepseek.com/v1}，代码会自动拼 /chat/completions），模型填
 * {@code deepseek-chat}（通用）或 {@code deepseek-reasoner}（推理）。</p>
 */
public class DeepSeekProvider extends OpenAIProvider {

    public DeepSeekProvider(String baseUrl, String apiKey, String model, ObjectMapper objectMapper,
                            ToolExecutor toolExecutor, HttpClientFactory httpClientFactory) {
        super(baseUrl, apiKey, model, objectMapper, toolExecutor, httpClientFactory);
    }

    @Override
    public String name() {
        return "deepseek";
    }
}
