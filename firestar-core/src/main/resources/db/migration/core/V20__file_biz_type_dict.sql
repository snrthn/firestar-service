-- ============================================================
-- V20：文件业务类型字典（file_biz_type）+ 字典状态域值残留归一
--
-- 背景（虎哥 2026-10-16）：文件上传弹窗的「业务类型」原是自由文本输入框
--   （placeholder 提示 invoice/receipt），且字典里没有对应类型 —— 前端已改为
--   字典下拉（useDict 消费样板），本迁移补齐字典类型与种子数据。
--
-- 取值口径：与业务主链模块对齐（建表方案.md 第六章），value 统一小写 ——
--   后端 FileServiceImpl.buildObjectKey 对空 bizType 归 "common"（小写），
--   objectKey 目录与存量数据同口径；后续新模块直接在字典维护页加选项。
--
-- ⚠️ 状态域值（V17 同款收口，勿删）：
--   V2/V19 的种子把 status 写成中文'启用'，而代码域值只有 ENABLED/DISABLED
--   （DictServiceImpl.applyDataCheck 白名单 + 前端 useDict 只消费 status==='ENABLED'）。
--   V19 晚于 V17 执行，其 login_status/file_status/storage_type 共 8 行
--   '启用' 残留躲过了 V17 —— 本迁移幂等补刀，新环境/老环境都归一。
--
-- 幂等：INSERT 走 ON DUPLICATE KEY（同 V2/V19 风格）；UPDATE 命中 0 行也无副作用。
-- 依赖：V2（sys_dict_type / sys_dict_data 已建）
-- ============================================================

INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('file_biz_type', '文件业务类型', 'ENABLED', '文件归属的业务模块（sys_file_storage.biz_type）', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`);

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('file_biz_type', '通用',     'common',     1, 1, 'ENABLED', 0),
('file_biz_type', '银行回单', 'receipt',    2, 0, 'ENABLED', 0),
('file_biz_type', '发票',     'invoice',    3, 0, 'ENABLED', 0),
('file_biz_type', '应收单',   'receivable', 4, 0, 'ENABLED', 0),
('file_biz_type', '核销单',   'writeoff',   5, 0, 'ENABLED', 0),
('file_biz_type', '发运单',   'shipment',   6, 0, 'ENABLED', 0),
('file_biz_type', '退货单',   'return',     7, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `is_default` = VALUES(`is_default`), `status` = VALUES(`status`);

-- 状态域值残留归一（V17 收口的补刀：修 V2/V19 中文种子在新环境的残留）
UPDATE `sys_dict_data` SET `status` = 'ENABLED' WHERE `status` = '启用';
UPDATE `sys_dict_data` SET `status` = 'DISABLED' WHERE `status` = '停用';
UPDATE `sys_dict_type` SET `status` = 'ENABLED' WHERE `status` = '启用';
UPDATE `sys_dict_type` SET `status` = 'DISABLED' WHERE `status` = '停用';
