-- ============================================================================
-- V51：发货相关字典补 style（标签取色，对齐 useDictLabels 约定）。
-- 背景：V2 种子建 shipment_status / shipment_line_status 时未带 style 列，
--       导致后端 toVO 回填的 shipmentStatusStyle / lineStatusStyle 恒为 NULL，
--       前端 el-tag 只能兜底 'info'（全灰）。本迁移按 type_code+dict_value 幂等 UPDATE，
--       字典维护页改色后仍即时生效（本迁移只给初始色，不锁死）。
-- 颜色语义：待发货/待发货确认/部分收货 = warning（进行中）；已发货 = primary；
--           全部收货 = success（终态达成）；已驳回 = danger。
-- ============================================================================

UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'shipment_status' AND `dict_value` = 'PENDING';
UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'shipment_status' AND `dict_value` = 'PENDING_CONFIRM';
UPDATE `sys_dict_data` SET `style` = 'primary' WHERE `type_code` = 'shipment_status' AND `dict_value` = 'SHIPPED';
UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'shipment_status' AND `dict_value` = 'PARTIAL_RECEIVED';
UPDATE `sys_dict_data` SET `style` = 'success' WHERE `type_code` = 'shipment_status' AND `dict_value` = 'FULL_RECEIVED';
UPDATE `sys_dict_data` SET `style` = 'danger'  WHERE `type_code` = 'shipment_status' AND `dict_value` = 'REJECTED';

UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'shipment_line_status' AND `dict_value` = 'PENDING';
UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'shipment_line_status' AND `dict_value` = 'PARTIAL_SHIPPED';
UPDATE `sys_dict_data` SET `style` = 'primary' WHERE `type_code` = 'shipment_line_status' AND `dict_value` = 'SHIPPED';
UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'shipment_line_status' AND `dict_value` = 'PARTIAL_RECEIVED';
UPDATE `sys_dict_data` SET `style` = 'success' WHERE `type_code` = 'shipment_line_status' AND `dict_value` = 'FULL_RECEIVED';
