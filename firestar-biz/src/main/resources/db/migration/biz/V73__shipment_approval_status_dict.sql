-- ============================================================================
-- V73：发货审批状态字典 shipment_approval_status（镜像 project_approval_status）。
--
-- 背景：发货单此前只有 shipment_status（业务生命周期：PENDING/SHIPPED/...），
-- 审批状态 approvalStatus 直接写裸码（APPROVING/APPROVED/...），前端没有对应的
-- 字典翻译 → 审批状态列只能显示裸英文，且无从统一配色。
-- 本次补一套与立项完全对齐的审批状态字典，发货详情/列表可渲染成带色的 el-tag。
--
-- 设计（虎哥 2026-10-26 拍板）：审批归审批、业务归业务，两列拆分展示。
--   - 草稿 DRAFT：新建发货单落库即此态，详情面板点「提交审批」才起流程（不再新建即发）
--   - 待审批 PENDING_APPROVAL / 审批中 APPROVING / 已驳回 REJECTED（可改重报）
--   / 审批通过 APPROVED（可标记发货）/ 已撤销 CANCELLED
--
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE；style 纯 UPDATE，可重复执行。
-- 不增删表/不改动计数（DatabaseCheckService 不跟踪字典行数，dictDataCount 不变）。
-- ============================================================================

-- 1. 字典类型
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('shipment_approval_status', '发货审批状态', '启用', '发货单审批状态（草稿/待审批/审批中/已驳回/审批通过/已撤销）', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`), `remark` = VALUES(`remark`);

-- 2. 字典数据 6 项（DRAFT is_default=1 承接「未提交」语义，与 project_approval_status 对齐）
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('shipment_approval_status', '草稿',     'DRAFT',             1, 1, '启用', 0),
('shipment_approval_status', '待审批',   'PENDING_APPROVAL',  2, 0, '启用', 0),
('shipment_approval_status', '审批中',   'APPROVING',         3, 0, '启用', 0),
('shipment_approval_status', '已驳回',   'REJECTED',          4, 0, '启用', 0),
('shipment_approval_status', '审批通过', 'APPROVED',          5, 0, '启用', 0),
('shipment_approval_status', '已撤销',   'CANCELLED',         6, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `dict_value` = VALUES(`dict_value`), `sort_no` = VALUES(`sort_no`), `is_default` = VALUES(`is_default`), `status` = VALUES(`status`);

-- 3. style 配色（镜像 V45 project_approval_status：info/info/warning/danger/success/info）
UPDATE `sys_dict_data` SET `style` = 'info'    WHERE `type_code` = 'shipment_approval_status' AND `dict_value` = 'DRAFT';
UPDATE `sys_dict_data` SET `style` = 'info'    WHERE `type_code` = 'shipment_approval_status' AND `dict_value` = 'PENDING_APPROVAL';
UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'shipment_approval_status' AND `dict_value` = 'APPROVING';
UPDATE `sys_dict_data` SET `style` = 'danger'  WHERE `type_code` = 'shipment_approval_status' AND `dict_value` = 'REJECTED';
UPDATE `sys_dict_data` SET `style` = 'success' WHERE `type_code` = 'shipment_approval_status' AND `dict_value` = 'APPROVED';
UPDATE `sys_dict_data` SET `style` = 'info'    WHERE `type_code` = 'shipment_approval_status' AND `dict_value` = 'CANCELLED';
