-- ============================================================================
-- V209：补齐 audit_target_type 字典的 KB_DOC（步骤 4.5 @Auditable 新增目标类型）
--
-- 背景（2026-11-09，由 AuditDictConsistencyTest.targetTypesAreInSync 直接拦下）：
--   步骤 4.5 新增 KbDocService，其 create / update / rename / move / delete / publish
--   六个写操作都标了 @Auditable(targetType = "KB_DOC")。该注解值是自由字符串，
--   而 audit_target_type 字典里没有 KB_DOC —— 操作日志页会回退显示英文原始值、
--   下拉也筛不到（与 V69 补 4 项、V98 补 SHIPMENT、V201 补 AI 两项同款）。
--   AuditDictConsistencyTest 做代码↔字典双向差集，本项缺失会让 CI 直接 fail。
--
-- ⚠️ 版本号：core / biz 双 location 共用一套全局版本序列，当前 core 最大 V208、
--    biz 最大 V200，故本迁移取 V209，放 core（历次 audit_target_type 补漏都在 core）。
-- ⚠️ 计数影响（已同步 DatabaseCheckService.EXPECTED_DICT_DATA_COUNT）：
--    净 +1 条 sys_dict_data，不新增字典类型、不建表
--    → tableCount 保持 51、dictTypeCount 保持 36、dictDataCount 154→155。
--    漏改常量会让 bin/test.sh 全量重放 / 首屏 db-check 探针误报（V19/V20/V22 三次事故）。
-- 依赖：V22（audit_target_type 字典类型已建）、V206/V207/V208（知识库存储层）
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE（唯一键 tenant_id + type_code + dict_value）
-- ============================================================================

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('audit_target_type', '知识库文档', 'KB_DOC', 13, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`),
                        `is_default` = VALUES(`is_default`), `status` = VALUES(`status`);

-- 备注维持 V201 的「只指路、不手写枚举清单」口径，杜绝第 5 处双写。
