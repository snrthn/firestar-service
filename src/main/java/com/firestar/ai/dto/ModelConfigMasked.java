package com.firestar.ai.dto;

/** 【模型配置】页返回给前端的脱敏视图：密钥只给"是否设置"标记，绝不下发明文或密文。 */
public record ModelConfigMasked(
        Long id,
        String provider,
        Integer active,
        String baseUrl,
        String model,
        boolean botIdSet,
        boolean apiKeySet,
        boolean extraSet) {
}
