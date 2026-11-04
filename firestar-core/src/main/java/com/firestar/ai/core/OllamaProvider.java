package com.firestar.ai.core;

import com.firestar.ai.spi.ChatChunk;
import com.firestar.ai.spi.ChatProvider;
import com.firestar.ai.spi.TurnRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Consumer;

/**
 * 内网私有供应商占位（数据不出域）。
 *
 * <p>阶段一决策（见 docs/AI助手集成设计.md §2#11）：L3 自然语言问数仍走云端供应商，
 * 全项目无需 GPU、不引入本地 Ollama。本类保留为供应商适配层的一个可丢弃实现——
 * 将来若需数据不出域，在此填充 Ollama 本地推理调用（复用 OpenAIProvider 的 SSE 解析骨架，
 * 仅换 base-url 与响应字段）即可，业务 / 前端 / 审计零改动。</p>
 */
public class OllamaProvider implements ChatProvider {

    private final String baseUrl;
    private final String model;

    public OllamaProvider(String baseUrl, String model, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public String createSession(String userRef) {
        return "ollama-" + userRef;
    }

    @Override
    public void streamChat(TurnRequest req, Consumer<ChatChunk> onChunk) {
        throw new UnsupportedOperationException(
                "Ollama 供应商阶段一未启用（决策：L3 走云端，无需本地 GPU）。"
                        + "如需数据不出域，请填充 OllamaProvider 的本地推理调用。");
    }
}
