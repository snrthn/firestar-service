package com.firestar.ai.spi;

/** 单条对话消息（供应商无关）。role ∈ user | assistant | system | tool。 */
public record ChatMessage(String role, String content) {
    public static ChatMessage of(String role, String content) {
        return new ChatMessage(role, content);
    }
}
