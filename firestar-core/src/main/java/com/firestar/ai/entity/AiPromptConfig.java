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
 * AI 提示词方案（sys_ai_prompt，V105 新增）。
 * 多套并存，is_active=1 全局唯一生效；content 为系统提示词正文，
 * 在对话首条 system 消息注入（OpenAI/DeepSeek 系原样生效，Coze 走前导指令尽力注入）。
 * 无生效行时回退 AiProperties.DEFAULT_CHAT_SYSTEM_INSTRUCTION。
 */
@Getter
@Setter
@TableName(value = "sys_ai_prompt")
public class AiPromptConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    /** 系统提示词正文 */
    private String content;

    /** 当前生效行（运行期真值）；映射 DB 列 is_active（V105 建表命名） */
    @TableField("is_active")
    private Integer active;

    private String createdBy;

    private LocalDateTime createdAt;

    private String updatedBy;

    private LocalDateTime updatedAt;
}
