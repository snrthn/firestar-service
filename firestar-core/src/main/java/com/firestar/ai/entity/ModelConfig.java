package com.firestar.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 模型供应商配置（sys_model_config，V99 新增）。
 * 密钥字段（botId / apiKey / extraJson）入库即加密（enc:v1: 前缀 + 国密 SM4-GCM）；
 * 页面回显一律掩码，运行期由【模型配置】页维护，不进 git / 不进前端。
 * KEK 不进本表，只存密文。
 */
@Getter
@Setter
@TableName(value = "sys_model_config")
public class ModelConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 模型类型：chat | embedding | rerank | tts（V214）。所有查询必须带此条件，避免跨类型串数据。 */
    private String modelType;

    /** coze | openai | deepseek | ollama | anthropic | dashscope */
    private String provider;

    /** 当前生效行（运行期真值）；映射 DB 列 is_active（V99 建表命名） */
    @TableField("is_active")
    private Integer active;

    private String baseUrl;

    private String model;

    /** 密文（仅 coze）：Bot ID */
    private String botId;

    /** 密文（仅 openai / coze pat）：令牌 / 密钥 */
    private String apiKey;

    /** 密文：其余参数(json) */
    private String extraJson;

    private String createdBy;

    private LocalDateTime createdAt;

    private String updatedBy;

    private LocalDateTime updatedAt;
}
