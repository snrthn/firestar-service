-- ============================================================
-- V54：文件业务类型字典补「签收证据」（shipment_receive_proof）
--
-- 背景（虎哥 2026-10-23）：发货模块「标记客户收货」弹窗上传签收证据时
--   bizType 硬编码 shipment_receive_proof（ShipmentServiceImpl 校验 + FileStorage 查询），
--   但 file_biz_type 字典里没有这个选项 —— 前端上传/文件管理按字典下拉渲染，
--   缺项导致该类型在界面上不可见、不可筛。
--
-- 取值口径：value 与后端字面量严格一致（shipment_receive_proof，含下划线，
--   同 V20 注释的口径——字典 value 就是落库的 biz_type，不许自行改写大小写/连字符）；
--   sort_no 接 V20 既有序列（return=7）之后。
--
-- 幂等：INSERT 走 ON DUPLICATE KEY（同 V20 风格），新环境/老环境都安全。
-- 依赖：V20（file_biz_type 类型已建）
-- ============================================================

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('file_biz_type', '签收证据', 'shipment_receive_proof', 8, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `is_default` = VALUES(`is_default`), `status` = VALUES(`status`);
