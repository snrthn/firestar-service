-- ============================================================================
-- V100：AI 助手会话与审计表（落自有库，不依赖扣子日志）
-- 背景：阶段一 L0+L1（设计见 docs/AI助手集成设计.md §8）。
--   扣子运行日志仅保留 7 天，不满足财务审计「可回放」要求，故会话/消息/工具调用全落自有库。
-- 设计要点：
--   - ai_conversations：一次会话一行；provider 记录实际使用的供应商（出域溯源）；
--     external_session_id 记录扣子 conversation_id 等，用于插件回调时反查当前登录用户。
--   - ai_messages：每轮 user/assistant/tool 消息一行；含 token 用量与耗时，便于成本核算与审计。
--   - ai_tool_calls：每次工具调用一行；arguments/result 全量留痕（谁、何时、调了什么、返回几行）。
-- 幂等：CREATE TABLE IF NOT EXISTS；无种子数据（会话由用户行为产生）。
-- 依赖：V1(baseline) → 本迁移仅新增独立表，无外键（会话归属靠 user_id 软关联）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `ai_conversations` (
  `id`                   BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `user_id`              BIGINT      NOT NULL COMMENT '发起会话的 FireStar 用户 id',
  `title`                VARCHAR(128) DEFAULT NULL COMMENT '会话标题(首条用户消息截断)',
  `provider`             VARCHAR(20) NOT NULL COMMENT '本次实际使用的供应商(coze|openai|ollama)',
  `external_session_id`  VARCHAR(128) DEFAULT NULL COMMENT '扣子 conversation_id 等(插件回调反查用户用)',
  `status`               TINYINT     NOT NULL DEFAULT 1 COMMENT '1进行中 2已结束',
  `created_at`           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 助手会话';

CREATE TABLE IF NOT EXISTS `ai_messages` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `conversation_id`  BIGINT      NOT NULL,
  `role`             VARCHAR(16) NOT NULL COMMENT 'user|assistant|tool',
  `content`          TEXT,
  `tokens_prompt`    INT         DEFAULT NULL,
  `tokens_completion` INT        DEFAULT NULL,
  `latency_ms`       INT         DEFAULT NULL,
  `model_version`    VARCHAR(64) DEFAULT NULL,
  `created_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 助手消息';

CREATE TABLE IF NOT EXISTS `ai_tool_calls` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `message_id`       BIGINT      NOT NULL,
  `conversation_id`  BIGINT      NOT NULL,
  `tool_name`        VARCHAR(64) NOT NULL,
  `arguments_json`   TEXT,
  `result_json`      TEXT,
  `rows_returned`    INT         DEFAULT NULL,
  `is_error`         TINYINT     NOT NULL DEFAULT 0,
  `latency_ms`       INT         DEFAULT NULL,
  `created_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 助手工具调用审计';
