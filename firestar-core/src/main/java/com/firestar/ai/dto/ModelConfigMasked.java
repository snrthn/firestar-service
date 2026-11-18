package com.firestar.ai.dto;

/**
 * 【模型配置】页返回给前端的脱敏视图。
 *
 * <p><b>密钥只给"是否设置"标记，绝不下发明文或密文</b>：api_key / bot_id 走
 * {@code xxxSet} 布尔位。</p>
 *
 * <p><b>但 extra_json 是例外 —— 回显明文</b>：extra 存的是「配置参数」而非密钥
 * （TTS 的 voice/format/sample_rate、embedding 的 dims），用户必须看得见才能改；
 * 前端编辑弹窗要按模型类型渲染结构化表单，也必须拿到原值才能做 merge（只覆盖
 * 结构化管理的字段，保留其余字段）。若此处只给「是否已配置」布尔，用户打开编辑
 * 会看到一个空表单，误填即覆盖丢失原参数。</p>
 *
 * <p>extra_json 在库内是密文（{@code enc:v1:}），这里返回的是解密后的明文 JSON 文本；
 * 若历史数据为明文，解密函数兼容原样返回。</p>
 */
public record ModelConfigMasked(
        Long id,
        String modelType,
        String provider,
        Integer active,
        String baseUrl,
        String model,
        boolean botIdSet,
        boolean apiKeySet,
        boolean extraSet,
        String extraJson) {
}
