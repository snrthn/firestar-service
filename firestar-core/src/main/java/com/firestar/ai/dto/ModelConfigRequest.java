package com.firestar.ai.dto;

/**
 * 【模型配置】页提交体。敏感字段（botId / apiKey / extraJson）允许为空：
 * 为空表示"保留库里已有密文、不覆盖"，只有非空时才重新加密写入（见 ModelConfigService）。
 * 前端永远拿不到明文/密文，仅回显"是否已设置"标记（ModelConfigMasked）。
 *
 * <p>V214：新增 modelType（chat|embedding|rerank|tts），缺省按 chat 处理以兼容旧客户端。</p>
 */
public record ModelConfigRequest(
        Long id,
        String modelType,
        String provider,
        String baseUrl,
        String model,
        String botId,
        String apiKey,
        String extraJson,
        /** 是否设为当前类型下的生效供应商（每类型唯一，运行期真值） */
        Integer active) {

    /** 兼容旧客户端：未传 modelType 时按 chat。 */
    public String effectiveModelType() {
        return (modelType == null || modelType.isBlank()) ? "chat" : modelType;
    }
}
