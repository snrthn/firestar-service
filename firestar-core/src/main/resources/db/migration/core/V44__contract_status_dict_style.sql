-- ============================================================================
-- V44：合同签约状态字典补 style（标签色）
--
-- 背景：sys_dict_data.style 列（V1 基线，字典 dict_style：
-- primary/success/warning/danger/info）本就是给前端 el-tag 用的
-- （dict-data.vue 管理页原话「控制前端标签颜色」），doc_direction /
-- receipt_status / return_approval_status 等财务字典都种了 style，
-- 唯独 project_contract_status 的 V2 种子漏种；且前端 useDict 的
-- DictOption 此前只取 {value, label}，业务页无 style 消费通道。
-- 本迁移补种 style；配套前端改动（useDict 扩展 style 字段）已落地。
--
-- 幂等：纯 UPDATE，可重复执行；不动任何计数（DatabaseCheckService 不跟踪 style）。
-- ============================================================================

UPDATE `sys_dict_data` SET `style` = 'info'    WHERE `type_code` = 'project_contract_status' AND `dict_value` = 'UNSIGNED';
UPDATE `sys_dict_data` SET `style` = 'success' WHERE `type_code` = 'project_contract_status' AND `dict_value` = 'SIGNED';
