-- ============================================================
-- V64：receipt_status 字典补 CANCELLED（收款撤回终态，虎哥 2026-10-25 指出 V63 漏了收款侧）
--
-- 背景：
--   收款单只有一个状态列 fin_receipt.approval_status（V1 baseline L242 注释即「字典 receipt_status」）。
--   ReceiptServiceImpl 实际写入 5 个字面量：
--     PENDING_APPROVAL(L143 创建) / CASHIER_APPROVING(L182 提交审批) / CANCELLED(L255 撤回)
--     RECEIVED(L282 审批通过) / REJECTED(L320 审批驳回)
--   V2 种子种了 6 条：PENDING_APPROVAL / CASHIER_APPROVING / RECEIVED /
--   PARTIAL_WRITEOFF / FULL_WRITEOFF / REJECTED —— 唯独缺 CANCELLED。
--   （PARTIAL_WRITEOFF / FULL_WRITEOFF 是核销维度由别处写入，本迁移不动。）
--
--   后果同 V63：撤回后的回款单，ReceiptDetailDialog / 列表页的 useDictLabels('receipt_status')
--   查不到 label/style → 状态 tag 文字空、颜色空；状态筛选下拉也筛不到撤回态。
--
-- ⚠️ 这是「审批流字典种子漏 CANCELLED」的第四回：V50 补 shipment REJECTED、
--   V56 补 shipment CANCELLED、V63 补 invoice CANCELLED+REJECTED、本次补 receipt CANCELLED。
--   根因一致：撤回/驳回是后加的动作（2026-10-23 撤回方案 B），而字典种子是 V2 一次性种完的。
--   判据照旧：grep setApprovalStatus/setInvoiceStatus 的字面量集合 == 字典 dict_value 集合。
--
-- ⚠️ sort_no 沿用 V2 既有 1~6（REJECTED=6）续排为 7，不重排旧值（改旧值会动前端下拉顺序）。
-- ⚠️ style='info'：撤回是中性终态（申请人主动终结），不是异常 —— 与 V63 的 invoice CANCELLED 口径一致。
--
-- ⚠️ 字典计数账（DatabaseCheckService 常量同步）：sys_dict_data 137→138，dictTypeCount 不变。
--
-- 幂等：INSERT 走 ON DUPLICATE KEY（同 V20/V54/V62/V63 风格）。
-- 依赖：V2（receipt_status 字典类型与六条种子）、V60（receipt_approval 流程）
-- ============================================================

INSERT INTO `sys_dict_data`
    (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `style`, `status`, `tenant_id`) VALUES
('receipt_status', '已撤销', 'CANCELLED', 7, 0, 'info', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE
    `dict_label` = VALUES(`dict_label`),
    `sort_no`    = VALUES(`sort_no`),
    `style`      = VALUES(`style`),
    `status`     = VALUES(`status`);
