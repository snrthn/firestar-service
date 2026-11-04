-- ============================================================================
-- V104：AI 供应商字典（前端【模型配置】供应商下拉 + 标签颜色的唯一真源，替代硬编码）
--
-- 背景：此前 model-config.vue 把 coze/openai/deepseek/ollama 四个供应商写死在
--       <el-option> 与 providerLabel() 里。新增供应商需前后端都改代码。
--       现把供应商列表沉淀到字典 ai_provider，前端走 DictSelect/useDictLabels 消费，
--       新增供应商只需在字典加一行（dict_value 必须与后端 provider 域值一致）。
--       ChatProviderManager 仍保留 Map<String,Provider> 注册表（能力边界，无法靠字典消除）。
--
-- ⚠️ 字典 dict_value 必须与 ModelConfig.provider 域值完全一致（coze/openai/deepseek/ollama），
--   否则 ChatProviderManager 注册表无对应实现 → 运行时抛"未知供应商类型"。
--
-- 依赖：V1 基线（sys_dict_type/sys_dict_data 已建）。
-- 幂等：sys_dict_type.type_code、sys_dict_data(type_code,dict_value) 均有唯一键，重复执行安全。
-- 不改变表结构，DatabaseCheckService.EXPECTED_TABLE_COUNT 不变。
-- ============================================================================

-- ========== 1. 字典类型 ==========
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('ai_provider', 'AI供应商', 'ENABLED', 'AI模型配置供应商下拉（替代前端硬编码）', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`), `remark` = VALUES(`remark`);

-- ========== 2. 字典数据（dict_value 即后端 provider 域值） ==========
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `style`, `status`, `tenant_id`) VALUES
('ai_provider', '扣子 Coze', 'coze', 1, 0, 'primary', 'ENABLED', 0),
('ai_provider', 'OpenAI 兼容', 'openai', 2, 0, 'success', 'ENABLED', 0),
('ai_provider', 'DeepSeek', 'deepseek', 3, 0, 'warning', 'ENABLED', 0),
('ai_provider', 'Ollama', 'ollama', 4, 0, 'info', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `style` = VALUES(`style`), `status` = VALUES(`status`);
