-- ============================================================================
-- V99：AI 模型供应商配置表（密钥加密存储）
-- 背景：阶段一引入 AI 助手（设计见 docs/AI助手集成设计.md §5）。
--   供应商配置（bot_id / api_key）属高敏感凭据，入库即加密（enc:v1: 前缀 + 国密 SM4-GCM），
--   页面回显一律掩码，运行期由【模型配置】页维护，不进 git、不进前端。
-- 设计要点：
--   - 每个 provider 一行（uk_provider 保证唯一），is_active 标记当前生效行（运行期真值）。
--   - DB 有 is_active 行 → 用它；无 → 回退 env 的 ai.provider（流水线注入的默认值）。
--   - KEK（加密密钥）绝不进本表，只存密文；KEK 来自环境变量 AI_KEK。
-- 幂等：CREATE TABLE IF NOT EXISTS，重复执行收敛到同一结构（Flyway 已保证只跑一次，
--       这里 IF NOT EXISTS 仅为本地手工补表兜底）。
-- 依赖：V1(baseline) → 本迁移仅新增独立表，无外键、无种子数据。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `sys_model_config` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `provider`    VARCHAR(20)  NOT NULL COMMENT 'coze|openai|ollama',
  `is_active`   TINYINT      NOT NULL DEFAULT 0 COMMENT '当前生效行(运行期真值)',
  `base_url`    VARCHAR(255) NOT NULL COMMENT '供应商 API 基址',
  `model`       VARCHAR(64)  DEFAULT NULL COMMENT '模型标识(如 qwen-max / qwen2.5:32b)',
  `bot_id`      VARCHAR(128) DEFAULT NULL COMMENT '密文(仅 coze)：Bot ID',
  `api_key`     VARCHAR(512) DEFAULT NULL COMMENT '密文(仅 openai / coze pat)：令牌/密钥',
  `extra_json`  TEXT         DEFAULT NULL COMMENT '密文：其余参数(json)',
  `created_by`  VARCHAR(64)  DEFAULT NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`  VARCHAR(64)  DEFAULT NULL,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_provider` (`provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模型供应商配置（密钥加密存储）';
