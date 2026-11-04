-- ============================================================================
-- V56：撤回通知模板 + 发货状态「已撤销」（配套 2026-10-23 虎哥拍板的撤回方案 B）
-- 依赖：V20（file_biz_type 无关，仅沿用其幂等风格）、V50（shipment_status 字典类型已建）
-- 幂等：模板走 uk_tpl_code 防重；字典走 (type_code, dict_value) 唯一约束防重
--
-- 背景：撤回 = 申请人主动终结审批流程（方案 B：流程未结束即可撤回）。
--   撤回后必须通知已审批过的人（否则其审批动作被无声抹掉 = 审计断链），
--   故本迁移种两条撤回通知模板。
--
-- ⚠️ template_name 必须给（V55 血泪教训）：V50 种 SHIPMENT_* 三模板时漏了该列，
--   通知记录页「类型」下拉吃模板列表、templateName 空则兜底显示裸 code，
--   界面直接裸奔 SHIPMENT_SUBMITTED。新模板一律带中文名。
--
-- 变量对齐业务层：
--   PROJECT_WITHDRAWN  {{bizTitle}} {{operator}}
--   SHIPMENT_WITHDRAWN {{batchNo}} {{projectName}} {{operator}}
-- ============================================================================

INSERT INTO `sys_notification_template`
    (`code`, `type`, `template_name`, `title_tpl`, `content_tpl`, `enabled`, `tenant_id`) VALUES
('PROJECT_WITHDRAWN',  'PROJECT_WITHDRAWN',  '立项撤回通知',
 '立项申请已撤回：{{bizTitle}}',
 '{{operator}} 撤回了立项申请（{{bizTitle}}），审批流程已终止。', 1, 0),
('SHIPMENT_WITHDRAWN', 'SHIPMENT_WITHDRAWN', '发货撤回通知',
 '发货申请已撤回：{{batchNo}}',
 '{{operator}} 撤回了发货单 {{batchNo}}（{{projectName}}），审批流程已终止。', 1, 0)
ON DUPLICATE KEY UPDATE
    `type`          = VALUES(`type`),
    `template_name` = VALUES(`template_name`),
    `title_tpl`     = VALUES(`title_tpl`),
    `content_tpl`   = VALUES(`content_tpl`),
    `enabled`       = VALUES(`enabled`);

-- ========== shipment_status 补 CANCELLED（撤回终态） ==========
-- 与 V2 既有 5 值 + V50 的 REJECTED(6) 同序 → CANCELLED(7)
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('shipment_status', '已撤销', 'CANCELLED', 7, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);
