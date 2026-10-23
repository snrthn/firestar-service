-- ============================================================================
-- V55：发货通知模板回填 template_name（修复通知记录「类型」下拉显示裸码）
-- 依赖：V50（SHIPMENT_SUBMITTED / SHIPMENT_APPROVED_GUIDE / SHIPMENT_REJECTED 模板已种）
-- 幂等：照 V31 模式——只回填名字为空的行，运营在管理端改过名的模板不被重置。
--
-- 根因（虎哥 2026-10-23 截图）：V50 的 INSERT 列序没有 template_name 列，三条发货
--   模板的名字落库为 NULL；通知记录页「类型」筛选下拉吃模板列表
--   （templateLabel：templateName 空 → 兜底显示 code），于是 SHIPMENT_SUBMITTED /
--   SHIPMENT_APPROVED_GUIDE / SHIPMENT_REJECTED 以裸码示人。
--   注意：这不是 sys_dict 字典缺行——下拉数据源是 sys_notification_template，
--   与 V31 给既有模板回填中文名同一机制。
--
-- 命名对齐既有风格（预算预警通知/开票完成通知/回款审核通知）。
-- 幂等性兜底：若本迁移被手动先行执行，Flyway 重跑时命中 0 行也无副作用。
-- ============================================================================

UPDATE `sys_notification_template` SET `template_name` = '发货审批通知'
  WHERE `code` = 'SHIPMENT_SUBMITTED'      AND (`template_name` IS NULL OR `template_name` = '');

UPDATE `sys_notification_template` SET `template_name` = '发货审批通过通知'
  WHERE `code` = 'SHIPMENT_APPROVED_GUIDE' AND (`template_name` IS NULL OR `template_name` = '');

UPDATE `sys_notification_template` SET `template_name` = '发货驳回通知'
  WHERE `code` = 'SHIPMENT_REJECTED'       AND (`template_name` IS NULL OR `template_name` = '');
