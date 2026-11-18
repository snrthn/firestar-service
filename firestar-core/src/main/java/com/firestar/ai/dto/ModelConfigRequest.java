package com.firestar.ai.dto;

/**
 * 【模型配置】页提交体。敏感字段（botId / apiKey / extraJson）允许为空：
 * 为空表示"保留库里已有密文、不覆盖"，只有非空时才重新加密写入（见 ModelConfigService）。
 * 前端永远拿不到明文/密文，仅回显"是否已设置"标记（ModelConfigMasked）。
 *
 * <p>V214：新增 modelType（chat|embedding|rerank|tts），缺省按 chat 处理以兼容旧客户端。</p>
 *
 * <p>V215：新增 sortNo（列表排序号）。表单可直接填写：<b>null = 新增排到末尾 / 编辑保持不变</b>；
 * 页面「上移/下移」走 {@code /ai/model-config/sort} 整表重排（回写 1..N），两条路径并行不冲突。</p>
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
        Integer active,
        /** 列表排序号（升序，1..N 连续）；null = 新增排到末尾 / 编辑保持不变 */
        Integer sortNo) {

    /** 兼容旧客户端：未传 modelType 时按 chat。 */
    public String effectiveModelType() {
        return (modelType == null || modelType.isBlank()) ? "chat" : modelType;
    }
}
