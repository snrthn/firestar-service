-- ============================================================
-- V22：字典对齐真实业务数据（permission_action / dict_style / audit_target_type）
--
-- 背景（虎哥 2026-10-16，用户拍板「字典跟着数据走，已发生真实业务场景
-- 才是最正确的驱动」）：
--   1) permission_action：V2 种子为 VIEW/CREATE/UPDATE/DELETE/EXPORT/APPROVE，
--      但 V4 权限种子与全部存量权限记录的真实域值是 READ/WRITE/EXECUTE
--      （2026-10-16 实测 sys_permission：READ 15 行 / WRITE 19 行 / EXECUTE 3 行）。
--      字典不跟数据走 → 权限页下拉与存量数据对不上号。本迁移删旧 6 行、
--      按真实数据重建 3 行，并同步修正字典类型备注。
--   2) dict_style：原只有 primary/success/danger 三项，后端 DictDataRequest
--      白名单为 primary/success/info/warning/danger 五项（用户确认按 5 个、
--      以字典作为唯一数据源）——补 info/warning。
--   3) 新增 audit_target_type：后端 @Auditable 真实落库的 6 个目标类型
--      （USER/ROLE/MENU/DICT_TYPE/DICT_DATA/FILE，2026-10-16 源码全量扫描确认），
--      操作日志页「目标类型」下拉与表格标签改消费字典。
--
-- 依赖：V2（字典类型/数据种子）、V4（RBAC 权限种子）
-- 幂等：DELETE + INSERT ... ON DUPLICATE KEY UPDATE（唯一键 (tenant_id, type_code, dict_value)）
--
-- ⚠️ 计数影响（已用真实库对账，2026-10-16）：
--   迁移前 dictType=27 / dictData=99
--     - permission_action：6 → 3 行（-3）
--     - dict_style：       3 → 5 行（+2）
--     - audit_target_type：+1 类型 / +6 数据
--   迁移后 dictType=28 / dictData=104
--   → DatabaseCheckService.dictTypeCount 27→28、dictDataCount 99→104 必须同步
--     （该常量已三次因「迁移改了种子数却没同步这里」触发 503，切记！）。
-- ============================================================

-- 1) permission_action：删旧 6 行，按真实域值 READ/WRITE/EXECUTE 重建
DELETE FROM `sys_dict_data` WHERE `type_code` = 'permission_action';
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('permission_action', '读取', 'READ',    1, 1, 'ENABLED', 0),
('permission_action', '写入', 'WRITE',   2, 0, 'ENABLED', 0),
('permission_action', '执行', 'EXECUTE', 3, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`), `status`=VALUES(`status`);
UPDATE `sys_dict_type` SET `remark` = 'READ/WRITE/EXECUTE（V22 对齐 V4 权限种子真实域值）' WHERE `type_code` = 'permission_action';

-- 2) dict_style：补 info/warning，补齐后端白名单 5 项
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('dict_style', '信息', 'info',    4, 0, 'ENABLED', 0),
('dict_style', '警告', 'warning', 5, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`), `status`=VALUES(`status`);

-- 3) 新增 audit_target_type 字典类型与 6 个数据项（@Auditable 真实值）
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('audit_target_type', '审计目标类型', 'ENABLED', '操作日志目标类型：USER/ROLE/MENU/DICT_TYPE/DICT_DATA/FILE', 0)
ON DUPLICATE KEY UPDATE `type_name`=VALUES(`type_name`), `remark`=VALUES(`remark`), `updated_at`=CURRENT_TIMESTAMP;
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('audit_target_type', '用户',     'USER',      1, 0, 'ENABLED', 0),
('audit_target_type', '角色',     'ROLE',      2, 0, 'ENABLED', 0),
('audit_target_type', '菜单',     'MENU',      3, 0, 'ENABLED', 0),
('audit_target_type', '字典类型', 'DICT_TYPE', 4, 0, 'ENABLED', 0),
('audit_target_type', '字典数据', 'DICT_DATA', 5, 0, 'ENABLED', 0),
('audit_target_type', '文件',     'FILE',      6, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`), `status`=VALUES(`status`);
