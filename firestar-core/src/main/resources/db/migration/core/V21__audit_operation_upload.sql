-- ============================================================
-- V21：audit_operation 字典补 UPLOAD 项
--
-- 背景（虎哥 2026-10-16）：前端操作日志页「操作类型」改为字典消费
--   （useDict 统一收口），但 V2 种子的 audit_operation 只有
--   CREATE/UPDATE/DELETE/LOGIN/APPROVE/EXPORT 六项，缺 UPLOAD——
--   而后端 FileServiceImpl.uploadFile @Auditable(operationType = "UPLOAD")
--   真实落库，字典缺项会让下拉筛选少「上传」、表格标签裸显英文 UPLOAD。
--
-- 依赖：V2（audit_operation 字典类型已建）
-- 幂等：ON DUPLICATE KEY（同 V2/V20 风格）
-- ⚠️ 本迁移新增 1 行 sys_dict_data → DatabaseCheckService.dictDataCount
--   必须同步 98→99（该常量已两次因迁移未同步触发 503，切记同步）。
-- ============================================================

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('audit_operation', '上传', 'UPLOAD', 7, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);
