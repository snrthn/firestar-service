-- ============================================================================
-- V38：P3 审批工作流 · 字典 / 权限点 / 菜单（Step 2 配置节点）
-- 依赖：V1（sys_process_def 表已建）、V2（audit_operation 字典类型已建）
-- 幂等：全部 INSERT ... ON DUPLICATE KEY UPDATE，可重复执行
-- 决策：C 拍板「不授 ROLE_ADMIN」——权限点与菜单均由 RBAC 手动分配，本脚本不写 role 链接段。
-- ============================================================================

-- ========== 1. 字典类型：project_approval_status（V2 无此类型） ==========
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('project_approval_status', '项目审批状态', '启用', '项目立项审批状态', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`), `remark` = VALUES(`remark`);

-- ========== 2. 字典数据：project_approval_status 6 项（含 DRAFT） ==========
-- DRAFT=1 为表默认值（承接「未提交」语义），is_default=1
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('project_approval_status', '草稿',     'DRAFT',             1, 0, '启用', 0),
('project_approval_status', '待审批',   'PENDING_APPROVAL',  2, 1, '启用', 0),
('project_approval_status', '审批中',   'APPROVING',         3, 0, '启用', 0),
('project_approval_status', '已驳回',   'REJECTED',          4, 0, '启用', 0),
('project_approval_status', '审批通过', 'APPROVED',          5, 0, '启用', 0),
('project_approval_status', '已撤销',   'CANCELLED',         6, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `dict_value` = VALUES(`dict_value`), `sort_no` = VALUES(`sort_no`), `is_default` = VALUES(`is_default`), `status` = VALUES(`status`);

-- ========== 3. audit_operation 补 SUBMIT(8) / REJECT(10) ==========
-- 注意：APPROVE 在 V2 已存在（sort 5），不重复；UPLOAD 在 V21 已存在（sort 7）。
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('audit_operation', '提交', 'SUBMIT', 8,  0, '启用', 0),
('audit_operation', '驳回', 'REJECT', 10, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `dict_value` = VALUES(`dict_value`), `sort_no` = VALUES(`sort_no`), `status` = VALUES(`status`);

-- ========== 4. 权限点 2 个（action_type 同 V25 的 config:manage：READ/WRITE） ==========
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('process:view',   '流程定义查看', 'API', 'READ',  'ENABLED', 0),
('process:manage', '流程定义管理', 'API', 'WRITE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`), `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- ========== 5. 菜单：「流程管理」一级目录（CATALOG，与系统管理/审批中心平级）+ 子「流程定义」 ==========
-- 注：menu_type 合法值为 CATALOG（V18 已把历史 DIRECTORY 归一为 CATALOG），不是 DIRECTORY。
-- 目录项 route_path / component / perm_code 为 NULL（CATALOG 不注册路由，仅侧栏分组）。
-- id 91/92 已查库确认未占用（最新已用 id=90，见 V36）。
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(91, '流程管理', 0,  NULL,        NULL,            NULL,           'CATALOG', 50, 1, 'ENABLED', 'Workflow', 0),
(92, '流程定义', 91, '/process/def', 'process/list', 'process:view', 'MENU',    1, 1, 'ENABLED', 'List',     0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`), `icon` = VALUES(`icon`);

-- ========== 6. 角色授权：C 拍板「不授 ROLE_ADMIN」 ==========
-- 权限点 process:view / process:manage 与菜单 91/92 均交由 RBAC 手动分配，
-- 本段刻意省略（与 #6「不走特办」一致）。
