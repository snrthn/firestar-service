-- ============================================================
-- V63：invoice_status 字典补 CANCELLED / REJECTED（开票状态缺枚举，虎哥 2026-10-25 提出）
--
-- 背景：
--   InvoiceServiceImpl 实际写入 5 个 invoice_status 字面量：
--     PENDING(161 创建) / ISSUING(199 提交审批) / CANCELLED(264 撤回)
--     ISSUED(289 审批通过) / REJECTED(327 审批驳回)
--   而 V2 种子只种了 PENDING / ISSUING / ISSUED 三条 —— CANCELLED 与 REJECTED 双双缺失。
--
--   后果不是"下拉少两项"：
--     ① 撤回/驳回后的单据，InvoiceDetailDialog 的 useDictLabels('invoice_status') 查不到
--        label/style → el-tag 文字空、type 空，列表页同；
--     ② 列表页状态筛选下拉（DictSelect type-code="invoice_status"）压根筛不到这两态，
--        "已撤销的单据去哪了"在界面上无法回答。
--
-- ⚠️ 本类是第三回踩：V50 给 shipment_status 补 REJECTED、V56 给 shipment_status 补 CANCELLED、
--    receipt_status 则在 V2 就带全了 REJECTED。加新审批流时，字典种子与后端状态字面量必须
--    逐条对齐 —— 判据：grep setInvoiceStatus 的字面量集合 == 字典 dict_value 集合。
--
-- ⚠️ 排序号沿用 V2 三值（1/2/3）续排：CANCELLED=4、REJECTED=5。
--    不重排既有 sort_no（前端下拉按 sort_no 有序展示，改旧值会改变用户既有习惯）。
-- ⚠️ style 口径对齐 receipt_status（V2 L106：REJECTED='danger'）：
--    CANCELLED='info'（撤回是中性终态，不是异常）、REJECTED='danger'。
--    invoice_status 原有三条在 V2 未给 style，本迁移只给新增两条 —— 老三条不加，
--    避免同一字典内 style 口径反复变更。
--
-- ⚠️ 字典计数账（DatabaseCheckService 常量同步，历史已四次因漏同步误报）：
--    sys_dict_type 不变（invoice_status 类型 V2 已建）；sys_dict_data +2（133→137 口径见 DatabaseCheckService）。
--
-- 幂等：INSERT 走 ON DUPLICATE KEY（同 V20/V54/V62 风格），重复执行只刷新 label/style。
-- 依赖：V2（invoice_status 字典类型与三条种子）、V60（receipt_approval / invoice_approval 流程）
-- ============================================================

INSERT INTO `sys_dict_data`
    (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `style`, `status`, `tenant_id`) VALUES
('invoice_status', '已撤销', 'CANCELLED', 4, 0, 'info',   'ENABLED', 0),
('invoice_status', '已驳回', 'REJECTED',   5, 0, 'danger', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE
    `dict_label` = VALUES(`dict_label`),
    `sort_no`    = VALUES(`sort_no`),
    `style`      = VALUES(`style`),
    `status`     = VALUES(`status`);
