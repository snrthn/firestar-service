-- ============================================================================
-- V78 款项类型字典拆分（蓝字 / 红字 双字典）
-- ----------------------------------------------------------------------------
-- 背景（2026-10-27 虎哥拍板）：原 receipt_type 把「收款性质」与「退款/红冲」混在一个
--   字典里，导致两个业务矛盾：
--   ① 蓝字收款单下拉出现 多收款 / 退货退款（流出，与 inflow 矛盾；多收款本应是
--      金额 vs 合同的派生异常态，不是用户可选类型）；
--   ② 红冲单下拉仍出现 正常收款（docDirection=RED 却标正常收款，自相矛盾）。
--
-- 拆分方案：
--   receipt_type      → 蓝字收款款项性质：NORMAL 正常收款 / PREPAY 预收款
--   receipt_red_type  → 红字/退款类：REFUND 退货退款 / RED 红冲
--   OVERPAY 多收款     → 从可选类型中删除（超收由「累计已收 vs 合同额」派生，非手选项）
--
-- 业务影响边界（关键，确保无使用负担）：
--   * 存库的 receipt_type 列值保持不变（仍 NORMAL / PREPAY / REFUND / RED）；
--     本迁移只动字典「数据源」(sys_dict_data.type_code)，不改任何业务记录的列值。
--   * StatsServiceImpl 按列值 'REFUND' / doc_direction='RED' 取负的统计逻辑不受影响
--     （它读的是 Receipt 实体列，不是字典 type_code）。
--   * 历史 OVERPAY 记录：字典删除后前端合并标签器回退到原始值展示（不空白），
--     属可接受的历史残留，待业务侧另行数据修正。
--
-- 幂等：全部 INSERT 带 ON DUPLICATE KEY UPDATE，UPDATE/DELETE 自身幂等，可重复执行。
-- ============================================================================

-- 1) 新增字典类型：红冲款项类型
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`)
VALUES ('receipt_red_type', '红冲款项类型', '启用', '红字/退款类收款单款项性质', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `remark` = VALUES(`remark`), `updated_at` = CURRENT_TIMESTAMP;

-- 2) receipt_type 类型备注收敛为「蓝字」
UPDATE `sys_dict_type` SET `remark` = '蓝字收款款项性质（正常/预收）' WHERE `type_code` = 'receipt_type';

-- 3) REFUND 从 receipt_type 迁到 receipt_red_type（列值 REFUND 不变，仅字典归属切换）
UPDATE `sys_dict_data`
SET `type_code` = 'receipt_red_type',
    `dict_label` = '退货退款',
    `sort_no`    = 1,
    `is_default` = 1,
    `status`     = 'ENABLED',
    `remark`     = '红字收款单做退款单（虎哥决策#2），金额取负'
WHERE `tenant_id` = 0 AND `type_code` = 'receipt_type' AND `dict_value` = 'REFUND';

-- 4) 新增 RED 红冲（receipt_red_type）
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `remark`, `tenant_id`)
VALUES ('receipt_red_type', '红冲', 'RED', 2, 0, 'ENABLED', '红字冲销，冲减原蓝字收款', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`), `remark`=VALUES(`remark`);

-- 5) 删除 OVERPAY（多收款不再作为可选类型；超收由金额 vs 合同派生）
DELETE FROM `sys_dict_data` WHERE `tenant_id` = 0 AND `type_code` = 'receipt_type' AND `dict_value` = 'OVERPAY';

-- 6) 状态归一为 ENABLED（前端 useDict 仅消费 status='ENABLED'，与 V67 已种 REFUND 对齐）
UPDATE `sys_dict_data` SET `status` = 'ENABLED' WHERE `tenant_id` = 0 AND `type_code` IN ('receipt_type', 'receipt_red_type');
