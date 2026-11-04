-- ============================================================================
-- V59：fin_project 汇总列 + 发票类型列与字典（回款/开票模块地基，2026-10-24）
-- 依赖：V1（fin_project / fin_invoice / sys_dict_type / sys_dict_data 表已建）、V2（字典范式）
-- 背景：原结论「回款/开票审批通过时维护 fin_project 汇总列（非查询聚合）」此前从未落地；
--       发票缺「普票/专票」类型列与字典。本迁移补齐，供 P1/P2 回写使用。
-- 幂等：列用 ALTER（Flyway 单次顺序执行，开发库重置请用 clean）；字典用 ON DUPLICATE KEY 防重。
-- ============================================================================

-- 1. fin_project 汇总列（已收 / 已开 / 应收）
ALTER TABLE `fin_project`
    ADD COLUMN `collected_amount`  DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '已收款金额（回款审批通过累加）',
    ADD COLUMN `invoiced_amount`   DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '已开票金额（开票审批通过累加）',
    ADD COLUMN `receivable_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '应收金额（应收单生成时累加）';

-- 2. fin_invoice 发票类型列
ALTER TABLE `fin_invoice`
    ADD COLUMN `invoice_type` VARCHAR(32) NOT NULL DEFAULT 'NORMAL' COMMENT '发票类型：NORMAL 普通发票 / SPECIAL 增值税专用发票';

-- 3. 字典类型：发票类型
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`)
VALUES ('invoice_type', '发票类型', '启用', '发票类型：普通发票/增值税专用发票', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `remark` = VALUES(`remark`);

-- 4. 字典数据：发票类型
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
    ('invoice_type', '普通发票',         'NORMAL',  1, 1, '启用', 0),
    ('invoice_type', '增值税专用发票',   'SPECIAL', 2, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `is_default` = VALUES(`is_default`);
