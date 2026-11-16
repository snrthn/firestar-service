-- ============================================================================
-- V211：AI 供应商字典新增 Anthropic（原生 Messages API）
--
-- 背景：V104 把供应商沉淀到字典 ai_provider，前端【模型配置】下拉零改动消费。
--       本次新增原生 Anthropic 供应商（POST /v1/messages，x-api-key + anthropic-version），
--       字典加一行即可让前端下拉出现；后端实现已登记在 ChatProviderManager.registry。
--
-- ⚠️ dict_value 必须与后端 provider 域值 / ChatProvider.name() 完全一致（anthropic），
--    否则运行时 build() 抛"未知供应商类型"。
-- 幂等：sys_dict_data(type_code,dict_value) 唯一键，重复执行安全。
-- 不改变表结构，DatabaseCheckService.EXPECTED_TABLE_COUNT 不变。
-- ============================================================================

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `style`, `status`, `tenant_id`) VALUES
('ai_provider', 'Anthropic', 'anthropic', 5, 0, 'danger', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `style` = VALUES(`style`), `status` = VALUES(`status`);
