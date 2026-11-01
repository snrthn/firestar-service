-- ============================================================================
-- V98：发货动作升级为「三部曲」（选承运商 + 运单号 + 上传附件）
--
-- 背景（虎哥 2026-11-01）：原 markShipped 仅是一次状态置 SHIPPED，纯状态机推进在
--   审计例规审查上毫无凭据、极易暴露流程脆弱性。现要求标记已发货必须同时携带：
--     1) 承运商（字典 logistics_carrier 的 dict_value，用户必须从下拉选）
--     2) 物流运单号 tracking_no（必填 + 库内唯一，可去承运商核验真发没发）
--     3) 发货附件（bizType=shipment_dispatch，至少 1 件，留内部凭据）
--   与既有「标记客户收货必传签收证据（shipment_receive_proof）」对称。
--
-- 存储口径：
--   - fin_shipment 加 tracking_no / carrier 两列（软关联附件，沿用 V84 口径不在主表加路径列）；
--   - 附件复用 sys_file_storage(biz_type + biz_id)，新增 bizType=shipment_dispatch；
--   - 承运商走字典 logistics_carrier（10 个热门承运商，状态域值 ENABLED，禁用项不进下拉）；
--   - 审计接入：audit_target_type 补 SHIPMENT，使 markShipped 的 @Auditable 目标类型可消费字典。
--
-- 幂等：
--   - ALTER 走 Flyway 单次执行（同 V52 惯例，不加 IF NOT EXISTS 守卫）；
--   - 字典 INSERT 走 ON DUPLICATE KEY UPDATE（同 V2/V20 风格），重复执行无副作用。
--
-- ⚠️ 探针计数影响（务必同步 DatabaseCheckService.readSeedData 期望值，否则首页
--    /system/db-check 报 503，历史已五次踩坑）：
--   本迁移净增：dict 类型 +1（logistics_carrier）、dict 数据 +12
--     （logistics_carrier 10 项 + file_biz_type 1 项 shipment_dispatch
--      + audit_target_type 1 项 SHIPMENT）
--   → dictTypeCount 33→34、dictDataCount 130→142。
-- 依赖：V2（dict 基线）/ V20（file_biz_type）/ V22（audit_target_type）
-- ============================================================================

ALTER TABLE `fin_shipment`
  ADD COLUMN `tracking_no` VARCHAR(64) NULL COMMENT '物流运单号（标记已发货必填，库内唯一，可去承运商核验）' AFTER `shipped_by_id`,
  ADD COLUMN `carrier`     VARCHAR(32) NULL COMMENT '承运商（字典 logistics_carrier 的 dict_value，如 SF）' AFTER `tracking_no`;

-- ---- 承运商字典类型 ----
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('logistics_carrier', '物流承运商', 'ENABLED', '标记已发货必选承运商；value 即后端落库的 carrier（如 SF/YTO）', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`), `remark` = VALUES(`remark`);

-- ---- 10 个热门承运商（按市占/常见度排序）----
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('logistics_carrier', '顺丰速运', 'SF',   1, 0, 'ENABLED', 0),
('logistics_carrier', '圆通速递', 'YTO',  2, 0, 'ENABLED', 0),
('logistics_carrier', '中通快递', 'ZTO',  3, 0, 'ENABLED', 0),
('logistics_carrier', '申通快递', 'STO',  4, 0, 'ENABLED', 0),
('logistics_carrier', '韵达速递', 'YD',   5, 0, 'ENABLED', 0),
('logistics_carrier', '京东物流', 'JD',   6, 0, 'ENABLED', 0),
('logistics_carrier', '邮政EMS',  'EMS',  7, 0, 'ENABLED', 0),
('logistics_carrier', '百世快递', 'BEST', 8, 0, 'ENABLED', 0),
('logistics_carrier', '德邦快递', 'DBL',  9, 0, 'ENABLED', 0),
('logistics_carrier', '极兔速递', 'JT',  10, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);

-- ---- 发货附件业务类型（复用 file_biz_type，sort_no 接 contract=8 之后）----
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('file_biz_type', '发货附件', 'shipment_dispatch', 9, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);

-- ---- 审计目标类型补 SHIPMENT（使 markShipped 的 @Auditable 目标类型可消费字典）----
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('audit_target_type', '发货单', 'SHIPMENT', 7, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);
