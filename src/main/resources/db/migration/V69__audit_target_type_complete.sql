-- ============================================================
-- V69：补齐 audit_target_type 字典数据（对齐后端 @Auditable 真实落库值）
--
-- 背景（虎哥 2026-10-26，操作日志「目标类型」下拉/表格标签与真实数据对不上）：
--   前端 operation.vue 消费字典 audit_target_type 展示「目标类型」列与下拉，
--   V22 只种了 6 项（USER/ROLE/MENU/DICT_TYPE/DICT_DATA/FILE）。
--   后端源码全量扫描 @Auditable 真实落库的目标类型共 10 个，缺 4 项：
--     INVOICE  （InvoiceServiceImpl，发票模块 CRUD）
--     APPROVAL （发票/项目/收款的审批通过/驳回动作 APPROVE/REJECT）
--     RECEIPT  （ReceiptServiceImpl，收款模块 CRUD）
--     PROJECT  （ProjectServiceImpl，项目相关变更）
--   缺项导致这四类日志在表格里回退显示原始英文（useDictLabels 未命中返回原值），
--   下拉里也筛不到。本次补齐这 4 项。
--
-- 依赖：V22（audit_target_type 字典类型已建）
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE（唯一键 (tenant_id, type_code, dict_value)）
--
-- ⚠️ 计数影响（务必同步 DatabaseCheckService.readSeedData）：
--   净 +4 条字典数据（audit_target_type 6→10），不新增字典类型，
--   → dictDataCount 140→144（dictTypeCount 保持 33）。
-- ============================================================

-- 1) 补齐 audit_target_type 缺失的 4 个目标类型（@Auditable 真实值）
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('audit_target_type', '发票', 'INVOICE',   7, 0, 'ENABLED', 0),
('audit_target_type', '审批', 'APPROVAL', 8, 0, 'ENABLED', 0),
('audit_target_type', '收款', 'RECEIPT',   9, 0, 'ENABLED', 0),
('audit_target_type', '项目', 'PROJECT',   10, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`), `status`=VALUES(`status`);

-- 2) 同步字典类型备注，反映完整目标类型集合
UPDATE `sys_dict_type`
SET `remark` = '操作日志目标类型：USER/ROLE/MENU/DICT_TYPE/DICT_DATA/FILE/INVOICE/APPROVAL/RECEIPT/PROJECT'
WHERE `type_code` = 'audit_target_type';
