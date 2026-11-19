-- V217: ① ai_messages 增加 request_id（每次聊天请求生成，可与日志/供应商排障关联）；
--       ② ai_message_feedback 存当前用户对某条 AI 回答的赞踩（每人每条唯一，可取消）。
-- 说明：request_id 由后端入口生成并随 SSE meta 帧下发，不作为上下文回灌，仅供展示与排障。
-- 新增 ai_ 前缀业务表，业务表总数 51→52；字典/配置/角色未动，其余项沿用上一版基线。
-- db-check-baseline: table=52, dictType=37, dictData=162, config=14, role=9, roleConflict=4
ALTER TABLE `ai_messages`
  ADD COLUMN `request_id` VARCHAR(64) NULL AFTER `model_version`;

CREATE TABLE IF NOT EXISTS `ai_message_feedback` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `message_id`    BIGINT      NOT NULL COMMENT 'ai_messages.id（assistant 行）',
  `user_id`       BIGINT      NOT NULL,
  `feedback_type` VARCHAR(8)  NOT NULL COMMENT 'LIKE | DISLIKE',
  `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_msg_user` (`message_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 回答赞踩';
