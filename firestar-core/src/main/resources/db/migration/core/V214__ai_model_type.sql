-- ============================================================================
-- V214：AI 模型配置按类型扩展（chat / embedding / rerank / tts 四类统一凭据池）
--
-- 背景：V99 建 sys_model_config 时按「每 provider 一行」设计（uk_provider），
--       只服务对话模型。随着知识库向量化（Embedding）、语义重排（Rerank）、
--       语音合成（TTS）落地，同一供应商（如百炼 dashscope）需同时承载多种能力，
--       「每 provider 一行」放不下 —— 故引入 model_type 维度，唯一键改为
--       (model_type, provider)，使同一 provider 可存多类型配置行。
--
-- 设计要点：
--   - model_type 默认 'chat'：现有行（coze/deepseek）自动回填为对话类型，零数据迁移风险。
--   - is_active 语义从「全局唯一」改为「每类型唯一」：由 Service 层保证切换时
--     先把同类型其它行置 0（DB 唯一键不再约束 active，避免跨类型互相顶掉）。
--   - 所有查询必须带 model_type 条件，否则 chat 查询会串到 embedding/rerank/tts 行 ——
--     这是本次最大风险，Service/Manager 侧已同步加条件并有单测守护。
--   - KEK 仍复用 ai.kek（由环境变量 AI_KEK 注入，CryptoService），本迁移不涉及密钥。
--     注意：本脚本内禁止出现「美元符号 + 左花括号」形式的占位符（Flyway 会连注释一起做
--     占位符替换，而 spring.flyway.placeholders 未注入 AI_KEK，会导致解析期直接失败）。
--
-- 幂等：ADD COLUMN / DROP INDEX 用 information_schema 存在性判断包一层存储过程，
--       避免重复执行报错（Flyway 保证只跑一次，此处为本地手工补迁移兜底）。
-- 依赖：V99(sys_model_config) → V104(ai_provider 字典) → 本迁移。
-- 不改变表数量，DatabaseCheckService.EXPECTED_TABLE_COUNT 不变；但新增字典类型 ai_model_type。
-- ============================================================================

-- ---------- 1. sys_model_config 结构扩展 ----------
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_model_config' AND COLUMN_NAME = 'model_type'
);
SET @sql_add_col = IF(@col_exists = 0,
  'ALTER TABLE `sys_model_config` ADD COLUMN `model_type` VARCHAR(20) NOT NULL DEFAULT ''chat'' COMMENT ''模型类型: chat|embedding|rerank|tts'' AFTER `id`',
  'SELECT 1');
PREPARE stmt FROM @sql_add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 现有行回填 chat（新列默认已是 chat，这里显式写一遍保证语义清晰 / 幂等）
UPDATE `sys_model_config` SET `model_type` = 'chat' WHERE `model_type` IS NULL OR `model_type` = '';

-- 唯一键：uk_provider → uk_type_provider (model_type, provider)
SET @uk_old = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_model_config' AND INDEX_NAME = 'uk_provider'
);
SET @sql_drop_uk = IF(@uk_old > 0,
  'ALTER TABLE `sys_model_config` DROP INDEX `uk_provider`',
  'SELECT 1');
PREPARE stmt FROM @sql_drop_uk; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @uk_new = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_model_config' AND INDEX_NAME = 'uk_type_provider'
);
SET @sql_add_uk = IF(@uk_new = 0,
  'ALTER TABLE `sys_model_config` ADD UNIQUE KEY `uk_type_provider` (`model_type`, `provider`)',
  'SELECT 1');
PREPARE stmt FROM @sql_add_uk; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 模型类型字典 ai_model_type（前端下拉 + 标签颜色唯一真源） ----------
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('ai_model_type', 'AI模型类型', 'ENABLED', '模型配置类型下拉: chat推理/embedding向量/rerank重排/tts语音合成', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`), `remark` = VALUES(`remark`);

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `style`, `status`, `tenant_id`) VALUES
('ai_model_type', '推理模型', 'chat', 1, 1, 'primary', 'ENABLED', 0),
('ai_model_type', 'Embedding', 'embedding', 2, 0, 'success', 'ENABLED', 0),
('ai_model_type', 'Rerank', 'rerank', 3, 0, 'warning', 'ENABLED', 0),
('ai_model_type', '语音合成 TTS', 'tts', 4, 0, 'info', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `style` = VALUES(`style`), `status` = VALUES(`status`);

-- ---------- 3. ai_provider 字典补充 dashscope（Embedding/Rerank/TTS 共用的百炼供应商） ----------
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `style`, `status`, `tenant_id`) VALUES
('ai_provider', '阿里云百炼', 'dashscope', 6, 0, 'success', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `style` = VALUES(`style`), `status` = VALUES(`status`);

-- ---------- 4. 预置 TTS 配置行（用户拍板：预置一个 Provider） ----------
-- 预置一行 tts + dashscope 的占位配置：base_url 用百炼 WebSocket  inference 地址，
-- model 默认 cosyvoice-v3-flash，api_key 留空。
-- ⚠️ 凭据来源（2026-11-18 定）：四类模型（chat / embedding / rerank / tts）的端点、模型名、密钥
--    **统一由系统管理员在【模型配置】页录入**，写入即加密落库，DB 是唯一真源。
--    迁移脚本不写密钥，也不做 properties / 环境变量回填 —— 同一把 key 散落两处最容易出现
--    "改了配置忘了改库（或反之）"，生效口径说不清。所以本行 api_key 必须由管理员在页面补录后才能启用。
-- active=0：TTS 无业务消费方，默认不启用；将来接入时页面补 key 并启用即可。
-- 用 INSERT ... SELECT + NOT EXISTS 保证幂等（uk_type_provider 保护）。
INSERT INTO `sys_model_config`
  (`model_type`, `provider`, `is_active`, `base_url`, `model`, `bot_id`, `api_key`, `extra_json`, `created_by`, `created_at`, `updated_at`)
SELECT 'tts', 'dashscope', 0,
       'wss://dashscope.aliyuncs.com/api-ws/v1/inference', 'cosyvoice-v3-flash', NULL, NULL,
       '{"voice":"longanyang","format":"mp3","sample_rate":22050}',
       'flyway', NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_model_config` WHERE `model_type` = 'tts' AND `provider` = 'dashscope'
);
