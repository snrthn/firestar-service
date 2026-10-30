-- ============================================================================
-- V84：立项签约合同附件（2026-10-30 虎哥拍板）
--
-- 背景：立项完成签约时没有合同上传位 —— 「项目毕竟还是需要有合同支撑的」。
--   签约（POST /project/{id}/sign）新增必传字段 contractFileIds，至少 1 个文件；
--   后端 ProjectServiceImpl.bindContractFiles 统一改写 bizType/bizId 挂载。
--
-- 存储口径：沿用 V62 发票附件同构的**软关联**（sys_file_storage.biz_type + biz_id），
--   不在 fin_project 上加列 —— 单文件路径列（fin_invoice.invoice_file_path）已在 V62
--   被证明是死输入位，弃用模式，不重蹈。
--
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE（同 V20 风格），重复执行无副作用。
-- 依赖：V20（sys_dict_type / sys_dict_data 与 file_biz_type 类型已建）
-- ============================================================================

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('file_biz_type', '合同', 'contract', 8, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);
