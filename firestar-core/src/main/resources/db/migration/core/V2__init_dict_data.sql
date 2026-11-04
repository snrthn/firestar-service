-- ============================================================================
-- V2.0 字典与种子数据初始化 · 火星财务系统
-- ============================================================================
-- 依据：建表方案 v1.2 §5.1「字典类型清单」（22 类）
-- 原则：🔴 禁止在代码里硬编码枚举值，一律走字典表（纪律红线）
--
-- 幂等：全部使用 INSERT ... ON DUPLICATE KEY UPDATE，可重复执行
-- 依赖：V1__baseline.sql（sys_dict_type / sys_dict_data / sys_config / sys_role）
-- ============================================================================

-- ============================================================================
-- 第一部分：字典类型 22 类
-- ============================================================================
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`)
VALUES
('common_status',              '通用状态',           '启用', '启用/停用，多表通用', 0),
('doc_direction',              '单据方向',           '启用', '蓝字/红字，红冲单据方向', 0),
('receivable_status',          '应收状态',           '启用', '应收单生命周期', 0),
('receivable_source',          '应收来源',           '启用', '发货生成/退货红冲', 0),
('shipment_status',            '发货状态',           '启用', '发货单生命周期', 0),
('shipment_line_status',       '发货行状态',         '启用', '发货明细行状态', 0),
('receipt_status',             '收款审批状态',       '启用', '收款单审批流', 0),
('receipt_type',               '款项类型',           '启用', '正常/预收/多收', 0),
('invoice_status',             '开票状态',           '启用', '发票开具状态', 0),
('writeoff_status',            '核销状态',           '启用', '正常/已反核销', 0),
('writeoff_method',            '核销方式',           '启用', '自动/手工核销', 0),
('return_approval_status',     '退货审批状态',       '启用', '退货三级审批流', 0),
('return_execute_status',      '退货执行状态',       '启用', '红冲执行状态', 0),
('project_contract_status',    '合同签约状态',       '启用', '已签约/未签约', 0),
('data_scope',                 '数据范围',           '启用', '角色数据可见范围', 0),
('menu_type',                  '菜单类型',           '启用', '目录/菜单/按钮', 0),
('dict_style',                 '字典样式',           '启用', '前端标签样式', 0),
('file_type',                  '文件类型',           '启用', '回单/发票/附件', 0),
('audit_operation',            '审计操作类型',       '启用', '操作日志类型', 0),
('permission_resource',        '权限资源类型',       '启用', 'MENU/BUTTON/API', 0),
('permission_action',          '权限操作类型',       '启用', 'VIEW/CREATE/UPDATE等', 0),
('config_type',                '配置类型',           '启用', 'SYSTEM/BUSINESS', 0),
('boolean_flag',               '通用布尔',           '启用', '是/否，用于下拉展示', 0)
ON DUPLICATE KEY UPDATE
    `type_name` = VALUES(`type_name`),
    `remark`    = VALUES(`remark`),
    `updated_at`= CURRENT_TIMESTAMP;

-- ============================================================================
-- 第二部分：字典数据（按类型分组）
-- ============================================================================

-- --- 通用状态 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('common_status', '启用', 'ENABLED',  1, 1, '启用', 0),
('common_status', '停用', 'DISABLED', 2, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 单据方向（红冲核心）---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `style`, `is_default`, `status`, `tenant_id`)
VALUES
('doc_direction', '蓝字', 'BLUE',  1, 'primary', 1, '启用', 0),
('doc_direction', '红字', 'RED',   2, 'danger',  0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `style`=VALUES(`style`), `is_default`=VALUES(`is_default`);

-- --- 应收状态 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('receivable_status', '待确认',   'PENDING_CONFIRM', 1, 1, '启用', 0),
('receivable_status', '已确认',   'CONFIRMED',       2, 0, '启用', 0),
('receivable_status', '部分核销', 'PARTIAL_WRITEOFF',3, 0, '启用', 0),
('receivable_status', '全部核销', 'FULL_WRITEOFF',   4, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 应收来源 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('receivable_source', '发货生成', 'FROM_SHIPMENT',     1, 1, '启用', 0),
('receivable_source', '退货红冲', 'FROM_RETURN',       2, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 发货状态 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('shipment_status', '待发货',     'PENDING',        1, 1, '启用', 0),
('shipment_status', '待发货确认', 'PENDING_CONFIRM',2, 0, '启用', 0),
('shipment_status', '已发货',     'SHIPPED',        3, 0, '启用', 0),
('shipment_status', '部分收货',   'PARTIAL_RECEIVED',4, 0, '启用', 0),
('shipment_status', '全部收货',   'FULL_RECEIVED',  5, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 发货行状态 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('shipment_line_status', '待发货',   'PENDING',          1, 1, '启用', 0),
('shipment_line_status', '部分发货', 'PARTIAL_SHIPPED',  2, 0, '启用', 0),
('shipment_line_status', '已发货',   'SHIPPED',          3, 0, '启用', 0),
('shipment_line_status', '部分收货', 'PARTIAL_RECEIVED', 4, 0, '启用', 0),
('shipment_line_status', '全部收货', 'FULL_RECEIVED',    5, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 收款审批状态 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `style`, `is_default`, `status`, `tenant_id`)
VALUES
('receipt_status', '待审批',     'PENDING_APPROVAL',  1, 'primary', 1, '启用', 0),
('receipt_status', '出纳审批中', 'CASHIER_APPROVING', 2, 'primary', 0, '启用', 0),
('receipt_status', '已收款',     'RECEIVED',          3, 'success', 0, '启用', 0),
('receipt_status', '部分核销',   'PARTIAL_WRITEOFF',  4, 'success', 0, '启用', 0),
('receipt_status', '全部核销',   'FULL_WRITEOFF',     5, 'success', 0, '启用', 0),
('receipt_status', '已驳回',     'REJECTED',          6, 'danger',  0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `style`=VALUES(`style`), `is_default`=VALUES(`is_default`);

-- --- 款项类型 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `remark`, `tenant_id`)
VALUES
('receipt_type', '正常收款', 'NORMAL',   1, 1, '启用', '发货后付款，可直接核销', 0),
('receipt_type', '预收款',   'PREPAY',   2, 0, '启用', '未发货先付，挂着等发货', 0),
('receipt_type', '多收款',   'OVERPAY',  3, 0, '启用', '客户多付，挂账留待以后核销或退款', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`), `remark`=VALUES(`remark`);

-- --- 开票状态 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('invoice_status', '待开票', 'PENDING',  1, 1, '启用', 0),
('invoice_status', '开票中', 'ISSUING',  2, 0, '启用', 0),
('invoice_status', '已开票', 'ISSUED',   3, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 核销状态 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('writeoff_status', '正常',   'NORMAL',        1, 1, '启用', 0),
('writeoff_status', '已反核销', 'REVERSED',    2, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 核销方式 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('writeoff_method', '手工核销', 'MANUAL', 1, 1, '启用', 0),
('writeoff_method', '自动核销', 'AUTO',   2, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 退货审批状态（三级审批）---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `style`, `is_default`, `status`, `tenant_id`)
VALUES
('return_approval_status', '待审批',     'PENDING_APPROVAL',   1, 'primary', 1, '启用', 0),
('return_approval_status', '上级审批中', 'SUPERVISOR_APPROVING',2, 'primary', 0, '启用', 0),
('return_approval_status', '财务审批中', 'FINANCE_APPROVING',  3, 'primary', 0, '启用', 0),
('return_approval_status', '主管审批中', 'MANAGER_APPROVING',   4, 'primary', 0, '启用', 0),
('return_approval_status', '审批通过',   'APPROVED',            5, 'success', 0, '启用', 0),
('return_approval_status', '执行中',     'EXECUTING',           6, 'success', 0, '启用', 0),
('return_approval_status', '已完成',     'COMPLETED',           7, 'success', 0, '启用', 0),
('return_approval_status', '已驳回',     'REJECTED',            8, 'danger',  0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `style`=VALUES(`style`), `is_default`=VALUES(`is_default`);

-- --- 退货执行状态 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('return_execute_status', '未执行', 'NOT_EXECUTED', 1, 1, '启用', 0),
('return_execute_status', '执行中', 'EXECUTING',    2, 0, '启用', 0),
('return_execute_status', '已完成', 'COMPLETED',    3, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 合同签约状态 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('project_contract_status', '未签约', 'UNSIGNED', 1, 1, '启用', 0),
('project_contract_status', '已签约', 'SIGNED',   2, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 数据范围 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `remark`, `tenant_id`)
VALUES
('data_scope', '自己负责的', 'SELF',   1, 1, '启用', '只能看自己创建/负责的', 0),
('data_scope', '全部数据',   'ALL',    2, 0, '启用', '所有数据', 0),
('data_scope', '本部门',     'DEPT',   3, 0, '启用', 'V1 预留未使用', 0),
('data_scope', '自定义部门', 'CUSTOM', 4, 0, '启用', '自定义部门集合，二期', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`), `remark`=VALUES(`remark`);

-- --- 菜单类型 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('menu_type', '目录', 'DIRECTORY', 1, 0, '启用', 0),
('menu_type', '菜单', 'MENU',      2, 1, '启用', 0),
('menu_type', '按钮', 'BUTTON',    3, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 字典样式 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('dict_style', '主要',   'primary', 1, 1, '启用', 0),
('dict_style', '成功',   'success', 2, 0, '启用', 0),
('dict_style', '危险',   'danger',  3, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 文件类型 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('file_type', '银行回单', 'BANK_RECEIPT', 1, 0, '启用', 0),
('file_type', '发票文件', 'INVOICE',      2, 0, '启用', 0),
('file_type', '附件',     'ATTACHMENT',   3, 1, '启用', 0),
('file_type', '临时文件', 'TEMP',         4, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 审计操作类型 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('audit_operation', '新建',     'CREATE',  1, 0, '启用', 0),
('audit_operation', '修改',     'UPDATE',  2, 0, '启用', 0),
('audit_operation', '删除',     'DELETE',  3, 0, '启用', 0),
('audit_operation', '登录',     'LOGIN',   4, 0, '启用', 0),
('audit_operation', '审批',     'APPROVE', 5, 0, '启用', 0),
('audit_operation', '导出',     'EXPORT',  6, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 权限资源类型 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('permission_resource', '菜单',   'MENU',   1, 1, '启用', 0),
('permission_resource', '按钮',   'BUTTON', 2, 0, '启用', 0),
('permission_resource', '接口',   'API',    3, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 权限操作类型 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('permission_action', '查看', 'VIEW',   1, 1, '启用', 0),
('permission_action', '新建', 'CREATE', 2, 0, '启用', 0),
('permission_action', '修改', 'UPDATE', 3, 0, '启用', 0),
('permission_action', '删除', 'DELETE', 4, 0, '启用', 0),
('permission_action', '导出', 'EXPORT', 5, 0, '启用', 0),
('permission_action', '审批', 'APPROVE',6, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 配置类型 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('config_type', '系统配置', 'SYSTEM',   1, 1, '启用', 0),
('config_type', '业务配置', 'BUSINESS', 2, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- --- 通用布尔 ---
INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
('boolean_flag', '是', '1', 1, 0, '启用', 0),
('boolean_flag', '否', '0', 2, 1, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);

-- ============================================================================
-- 第三部分：系统配置 5 个核心键（业务模型第 15 章初始化安装）
-- ============================================================================
INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `remark`, `tenant_id`)
VALUES
('sys_initialized',         'false', 'SYSTEM', '系统是否已初始化（false→true）', 0),
('trial_mode',              'true',  'SYSTEM', '试运行模式（true→false 正式启用）', 0),
('force_change_password',   'true',  'SYSTEM', '是否强制改初始密码（true→false）', 0),
('last_reset_time',         NULL,    'SYSTEM', '上次系统重置时间', 0),
('last_reset_by',           NULL,    'SYSTEM', '上次系统重置操作人', 0)
ON DUPLICATE KEY UPDATE `remark`=VALUES(`remark`), `config_type`=VALUES(`config_type`);

-- ============================================================================
-- 第四部分：RBAC 基础角色（互斥约束的 4 组依赖这些角色存在）
-- ============================================================================
INSERT INTO `sys_role` (`role_code`, `role_name`, `data_scope`, `sort_no`, `status`, `remark`, `tenant_id`)
VALUES
('ROLE_ADMIN',      '系统管理员', 'ALL',  1, '启用', '系统最高权限', 0),
('ROLE_SALES_MANAGER','销售经理', 'SELF', 2, '启用', '负责项目与发货', 0),
('ROLE_CASHIER',     '出纳',       'SELF', 3, '启用', '负责收款与核销', 0),
('ROLE_INVOICER',    '开票员',     'SELF', 4, '启用', '负责开票', 0),
('ROLE_SALES',       '销售人员',   'SELF', 5, '启用', '项目下跟进', 0),
('ROLE_FINANCE',     '财务经理',   'ALL',  6, '启用', '退货财务审批', 0),
('ROLE_SUPERVISOR',  '主管',       'ALL',  7, '启用', '退货主管审批', 0)
ON DUPLICATE KEY UPDATE
    `role_name` = VALUES(`role_name`),
    `data_scope`= VALUES(`data_scope`),
    `sort_no`   = VALUES(`sort_no`),
    `remark`    = VALUES(`remark`);

-- ============================================================================
-- 第五部分：互斥角色约束 4 组
-- 用子查询取 role_id，避免硬编码自增 ID
-- ============================================================================
INSERT INTO `sys_role_conflict` (`role_a_id`, `role_b_id`, `conflict_desc`, `tenant_id`)
SELECT a.id, b.id, '销售经理与出纳职责分离', 0
FROM `sys_role` a, `sys_role` b
WHERE a.role_code = 'ROLE_SALES_MANAGER' AND b.role_code = 'ROLE_CASHIER'
ON DUPLICATE KEY UPDATE `conflict_desc` = VALUES(`conflict_desc`);

INSERT INTO `sys_role_conflict` (`role_a_id`, `role_b_id`, `conflict_desc`, `tenant_id`)
SELECT a.id, b.id, '开票员与出纳职责分离', 0
FROM `sys_role` a, `sys_role` b
WHERE a.role_code = 'ROLE_INVOICER' AND b.role_code = 'ROLE_CASHIER'
ON DUPLICATE KEY UPDATE `conflict_desc` = VALUES(`conflict_desc`);

INSERT INTO `sys_role_conflict` (`role_a_id`, `role_b_id`, `conflict_desc`, `tenant_id`)
SELECT a.id, b.id, '销售人员与出纳职责分离', 0
FROM `sys_role` a, `sys_role` b
WHERE a.role_code = 'ROLE_SALES' AND b.role_code = 'ROLE_CASHIER'
ON DUPLICATE KEY UPDATE `conflict_desc` = VALUES(`conflict_desc`);

INSERT INTO `sys_role_conflict` (`role_a_id`, `role_b_id`, `conflict_desc`, `tenant_id`)
SELECT a.id, b.id, '销售经理与开票员职责分离', 0
FROM `sys_role` a, `sys_role` b
WHERE a.role_code = 'ROLE_SALES_MANAGER' AND b.role_code = 'ROLE_INVOICER'
ON DUPLICATE KEY UPDATE `conflict_desc` = VALUES(`conflict_desc`);