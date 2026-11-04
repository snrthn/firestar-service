package com.firestar.ai.spi;

/** 供应商可选参数（模型标识等）。不在本类写死供应商专属字段，保持供应商无关。 */
public record ChatOptions(String model) {
    public static ChatOptions of(String model) {
        return new ChatOptions(model);
    }
}
