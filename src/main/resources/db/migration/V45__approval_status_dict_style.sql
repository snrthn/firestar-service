-- ============================================================================
-- V45：项目审批状态字典补 style（标签色）
--
-- 背景：与 V44（project_contract_status）同族——sys_dict_data.style 列
-- （V1 基线，注释「字典 dict_style：primary/success/danger」）就是给
-- 前端 el-tag 用的，但 V38 的 project_approval_status 6 行种子漏种 style，
-- 立项列表页审批状态列只能靠前端 statusTagType() 硬编码 switch 配色。
-- 本迁移补种 style，列表列改走 VO 透传的 approvalStatusStyle，
-- 字典维护页改色即生效（与合同状态列同一链路）。
--
-- 配色语义（对齐原 statusTagType switch，老用户无感迁移）：
--   草稿/待审批/已撤销 → info（灰）
--   审批中 → warning（橙）
--   已驳回 → danger（红）
--   审批通过 → success（绿）
--
-- 幂等：纯 UPDATE，可重复执行；不动任何计数（DatabaseCheckService 不跟踪 style）。
-- ============================================================================

UPDATE `sys_dict_data` SET `style` = 'info'    WHERE `type_code` = 'project_approval_status' AND `dict_value` = 'DRAFT';
UPDATE `sys_dict_data` SET `style` = 'info'    WHERE `type_code` = 'project_approval_status' AND `dict_value` = 'PENDING_APPROVAL';
UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'project_approval_status' AND `dict_value` = 'APPROVING';
UPDATE `sys_dict_data` SET `style` = 'danger'  WHERE `type_code` = 'project_approval_status' AND `dict_value` = 'REJECTED';
UPDATE `sys_dict_data` SET `style` = 'success' WHERE `type_code` = 'project_approval_status' AND `dict_value` = 'APPROVED';
UPDATE `sys_dict_data` SET `style` = 'info'    WHERE `type_code` = 'project_approval_status' AND `dict_value` = 'CANCELLED';
