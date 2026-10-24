-- ============================================================
-- V62：回款/发票「真上传」配套（字典状态归一 + 附件业务类型 + file:manage 授权）
--
-- 背景（虎哥 2026-10-25）：
--   ① fin_receipt.bank_receipt_path / fin_invoice.invoice_file_path 是 V1 baseline 留的
--      「只存路径」列 —— 系统里没有任何东西产出这个路径，纯死输入位。
--      拍板改「真上传」：附件走 sys_file_storage 软关联（bizType + bizId），
--      与 V54 发货签收证据（shipment_receive_proof）严格同构，业务表的 path 列废弃不写。
--   ② V59 种 invoice_type 字典时 status 误写成中文'启用'，而全库域值只有
--      ENABLED/DISABLED（DictServiceImpl 白名单 + 前端 useDict 只消费 status==='ENABLED'），
--      导致该字典项在界面上不可见 —— 本项目第三回踩同一个坑（V17 / V20 各一次的中文残留）。
--   ③ 附件 bizType 字面量必须落在 file_biz_type 字典里，否则文件管理页按字典下拉渲染时
--      这两个类型不可见、不可筛（V54 踩过的同一道题）。
--
-- ⚠️ 取值口径（同 V20/V54 注释，勿自行改写大小写/连字符）：
--   字典 dict_value == 后端写入 sys_file_storage.biz_type 的字面量。
--   本次复用 V20 已存在的 receipt / invoice 两项（后端 ReceiptServiceImpl / InvoiceServiceImpl
--   绑定附件时用的就是这两个字面量），故本迁移不新增字典行数，只幂等确保存在且启用。
--
-- ⚠️ 字典计数账（DatabaseCheckService 常量同步，历史上已四次因不同步而误报）：
--   本迁移不新增 sys_dict_type / sys_dict_data 行 → 计数不变；
--   V59 的漏账（type +1、data +2）在同一批次代码里补上，详见 DatabaseCheckService 注释。
--
-- 幂等：INSERT 走 ON DUPLICATE KEY（同 V20/V54 风格）；UPDATE 命中 0 行无副作用。
-- 依赖：V20（file_biz_type 类型已建）、V59（invoice_type 已建）、V60（ROLE_CASHIER/ROLE_INVOICER 授权范式）
-- ============================================================

-- 1. 字典状态域值归一（V17/V20 收口的第三次补刀：修 V59 中文种子在新/老环境的残留）
UPDATE `sys_dict_data` SET `status` = 'ENABLED' WHERE `status` = '启用';
UPDATE `sys_dict_data` SET `status` = 'DISABLED' WHERE `status` = '停用';
UPDATE `sys_dict_type` SET `status` = 'ENABLED' WHERE `status` = '启用';
UPDATE `sys_dict_type` SET `status` = 'DISABLED' WHERE `status` = '停用';

-- 2. 附件业务类型：幂等确保 receipt / invoice 两项存在且启用
--    （V20 已种 receipt=2 / invoice=3，这里只兜底 label 与 status，sort_no 沿用 V20 序列）
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('file_biz_type', '银行回单', 'receipt', 2, 0, 'ENABLED', 0),
('file_biz_type', '发票',     'invoice', 3, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE
    `dict_label` = VALUES(`dict_label`),
    `sort_no`    = VALUES(`sort_no`),
    `is_default` = VALUES(`is_default`),
    `status`     = VALUES(`status`);

-- 3. 上传权限：file:manage 目前只有 ROLE_ADMIN（V4 全量授权），
--    业务角色需各自 create/update 单据时把附件绑上去 —— 上传走 method 级 file:manage，
--    只持 file:view 会被 PermissionInterceptor 判 403。
--
--    ROLE_CASHIER  ：银行回单（回款单附件）
--    ROLE_INVOICER ：发票文件（发票单附件）
--    ROLE_SALES_MANAGER：发货签收证据（V53 注释写「文件上传/查看」，实际只授了 file:view，
--                    收货留痕上传按注释口径本就该放开 —— 顺手补齐，注释与授权对齐）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code IN ('ROLE_CASHIER', 'ROLE_INVOICER', 'ROLE_SALES_MANAGER')
  AND p.perm_code = 'file:manage'
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';
