-- V216: ai_messages 增加思考过程文本列。
-- 背景：OpenAIProvider 的 reasoning_content 已拆独立 SSE 通道（不混正文），但流式渲染完即丢；
--       回放历史会话时看不到思考过程。本列持久化思考文本，随回放接口下发，前端折叠展示。
-- 说明：思考文本可能比正文长（推理模型常见数千 token），用 MEDIUMTEXT；NULL = 无思考过程。
ALTER TABLE `ai_messages`
  ADD COLUMN `reasoning_text` MEDIUMTEXT NULL AFTER `content`;
