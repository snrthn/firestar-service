-- ============================================================
-- V201：补齐 audit_target_type 字典缺失的 AI 两项（@Auditable 真实值）
--
-- 背景（2026-11-04 彻查「为什么每次都会漏字典」）：
--   代码侧 @Auditable(targetType=...) 共 13 个字面量，字典侧 audit_target_type
--   只有 11 项，缺 AI_PROMPT / AI_MODEL_CONFIG 两项 —— 操作日志页这两类记录
--   回退显示英文原始值，下拉也筛不到（与 V69 补 4 项、V98 补 SHIPMENT 同款）。
--   这是同一个字典第 4 轮人工补漏，根因是「枚举与字典双写、无人校验」。
--   本次一并落地 AuditDictConsistencyTest（代码↔字典双向差集，接进 CI 门禁），
--   以后再加新 targetType 而漏字典，CI 会直接 fail。
--
-- 依赖：V22（audit_target_type 字典类型已建）
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE（唯一键 tenant_id + type_code + dict_value）
--
-- ⚠️ 版本号：core / biz 双 location 共用一套全局版本序列，当前最大为
--    V200__invoice_pending_issue_template_name.sql（biz），故本迁移取 V201。
--    放在 core 是因为历次 audit_target_type 字典迁移（V22/V69/V98）都在 core。
-- ⚠️ 计数影响（已同步 DatabaseCheckService.EXPECTED_DICT_DATA_COUNT）：
--    净 +2 条 sys_dict_data，不新增字典类型
--    → dictDataCount 146→148（dictTypeCount 保持 35）。
-- ============================================================

-- 1) 补齐 AI 模块两个目标类型
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('audit_target_type', 'AI 提示词',   'AI_PROMPT',       11, 0, 'ENABLED', 0),
('audit_target_type', 'AI 模型配置', 'AI_MODEL_CONFIG', 12, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`),
                        `is_default` = VALUES(`is_default`), `status` = VALUES(`status`);

-- 2) 字典类型备注不再手写完整枚举清单（历史 V22/V69 各维护过一份，均已过时）。
--    枚举的唯一事实来源是 @Auditable 注解，备注只指路，杜绝第 5 处双写。
UPDATE `sys_dict_type`
SET `remark` = '操作日志目标类型：枚举唯一事实来源为 @Auditable.targetType，字典项以 sys_dict_data 为准（由 AuditDictConsistencyTest 做双向差集校验）'
WHERE `type_code` = 'audit_target_type';
