-- ============================================================================
-- V105：AI 提示词管理表（sys_ai_prompt）
--
-- 背景：系统提示词（工具调用纪律）此前硬编码在 AiProperties.DEFAULT_CHAT_SYSTEM_INSTRUCTION
--       （Java 常量）。现抽成可运维的提示词方案表，支持多套并存、is_active=1 唯一生效，
--       启用哪套哪套在对话中实时生效（全局唯一，与模型配置 is_active 语义一致）。
--       无生效行时回退 AiProperties 常量（向后兼容）。
--
-- 列说明：
--   name     方案名称（展示用）
--   content  系统提示词正文
--   is_active 是否生效（全局唯一，1=生效；同一 tenant 仅一行可为 1）
--
-- 依赖：V1 基线（AuditedEntity 风格）。
-- 新增表 → DatabaseCheckService.EXPECTED_TABLE_COUNT 需 +1（见验证阶段同步）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `sys_ai_prompt` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `name`       VARCHAR(128) NOT NULL                COMMENT '提示词方案名称',
  `content`    TEXT         NOT NULL                COMMENT '系统提示词正文',
  `is_active`  TINYINT      NOT NULL DEFAULT 0      COMMENT '是否生效（全局唯一，1=生效）',
  `created_by` VARCHAR(64)  DEFAULT NULL,
  `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `updated_by` VARCHAR(64)  DEFAULT NULL,
  `updated_at` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`  BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_ai_prompt_active` (`is_active`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 提示词管理（全局唯一生效一套）';
