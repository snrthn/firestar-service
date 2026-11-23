-- ============================================================================
-- V220：立项合同变更单全栈地基（2026-11-23 虎哥拍板）
--
-- db-check-baseline: table=55, dictType=40, dictData=179, config=14, role=9, roleConflict=4
-- （本迁移新建 2 张 fin_ 业务表：fin_project_change / fin_project_change_item，
--   table 53→55；未动字典/配置/角色。三处同步：本标记 + db-check-baseline.properties + Java 常量）
--
-- 背景：立项合同金额手填，手滑多打 0（10 万填成 100 万）导致 90 万无明细支撑、
--       发货/收款核销不掉。方案定案：保留手填，但在立项详情增加「发起变更」入口，
--       把金额修正与明细增减收进一条独立的 Flowable 审批链（project_change），
--       审批通过后以增量 diff 应用到项目，实现账目重新自洽。
--
-- 设计纪律（对齐 V67 退货单）：
--   1. 变更单本体 + 明细 diff 两张表；审批期间项目主表/明细表不动，审批通过才应用；
--   2. 明细用「增量 diff」（ADD/MODIFY/DELETE）而非删除重建，保护 shippedQty 已发占用；
--   3. 流程独立：project_change（主管→经理两节点），不复用 project_initiate，
--      审批通过不改项目 approvalStatus / contractStatus，只应用金额与明细；
--   4. 窗口约束在应用层 applyChange 校验（窗口 A 自由改；窗口 B 改小不得低于已执行）；
--   5. 全部 INSERT ... ON DUPLICATE KEY UPDATE，幂等可重跑。
-- ============================================================================

-- ========== 1. fin_project_change 变更单主表 ==========
CREATE TABLE IF NOT EXISTS `fin_project_change` (
  `id`                  bigint        NOT NULL AUTO_INCREMENT,
  `change_no`           varchar(64)   NOT NULL COMMENT '变更单号 CHG-{yyyyMMdd}-{id四位}',
  `project_id`          bigint        NOT NULL COMMENT '项目 ID → fin_project.id',
  `change_reason`       varchar(500)  NOT NULL COMMENT '变更原因（必填，合规留痕）',
  `old_contract_amount` decimal(18,2) NOT NULL COMMENT '变更前合同金额',
  `new_contract_amount` decimal(18,2) NULL     COMMENT '变更后合同金额（NULL=本次不改金额，仅改明细）',
  `window_type`         varchar(16)   NOT NULL DEFAULT 'A' COMMENT '变更窗口 A/B（A=无下游单据自由改；B=已有单据受限）',
  `approval_status`     varchar(32)   NOT NULL DEFAULT 'DRAFT' COMMENT '审批状态（字典 approval_status）',
  `execute_status`      varchar(32)   NOT NULL DEFAULT 'PENDING' COMMENT '执行状态：PENDING/EXECUTED/CANCELLED',
  `process_instance_id` varchar(64)   NULL     COMMENT 'Flowable 流程实例 ID',
  `applicant_id`        bigint        NOT NULL COMMENT '发起人 ID',
  `applied_at`          datetime      NULL     COMMENT '发起审批时间',
  `executed_at`         datetime      NULL     COMMENT '变更应用时间',
  `tenant_id`           bigint        NOT NULL DEFAULT 0,
  `created_by`          bigint        NULL,
  `created_at`          datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`          bigint        NULL,
  `updated_at`          datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `version`             int           NOT NULL DEFAULT 0,
  `ext`                 json          NULL,
  `is_deleted`          tinyint(1)    NOT NULL DEFAULT 0,
  `deleted_at`          datetime      NULL,
  `deleted_by`          bigint        NULL,
  PRIMARY KEY (`id`),
  KEY `idx_change_project` (`project_id`, `is_deleted`),
  KEY `idx_change_applicant` (`applicant_id`),
  KEY `idx_change_status` (`approval_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='立项合同变更单（V220：受控金额/明细变更）';

-- ========== 2. fin_project_change_item 变更明细 diff 表 ==========
-- op_type=ADD：item_id 为空，insert_id 为空，新增一整行；
-- op_type=MODIFY：item_id 指向 fin_item.id，quantity/unit_price 为目标值；
-- op_type=DELETE：item_id 指向 fin_item.id，应用层仅允许 shippedQty=0 的行。
CREATE TABLE IF NOT EXISTS `fin_project_change_item` (
  `id`          bigint        NOT NULL AUTO_INCREMENT,
  `change_id`   bigint        NOT NULL COMMENT '变更单 ID → fin_project_change.id',
  `op_type`     varchar(16)   NOT NULL COMMENT '操作：ADD / MODIFY / DELETE',
  `item_id`     bigint        NULL     COMMENT '原明细行 ID（MODIFY/DELETE 必填；ADD 为空）',
  `item_name`   varchar(200)  NOT NULL COMMENT '货物/服务名称（ADD/MODIFY 目标值）',
  `spec_model`  varchar(200)  NULL     COMMENT '规格型号',
  `unit`        varchar(32)   NULL     COMMENT '单位',
  `quantity`    int           NOT NULL DEFAULT 0 COMMENT '目标数量（DELETE 时忽略）',
  `unit_price`  decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '目标单价（必填 >0）',
  `goods_id`    bigint        NOT NULL DEFAULT 0 COMMENT '关联货物主表 ID（0=服务行不进主表）',
  `tenant_id`   bigint        NOT NULL DEFAULT 0,
  `created_by`  bigint        NULL,
  `created_at`  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`  bigint        NULL,
  `updated_at`  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `version`     int           NOT NULL DEFAULT 0,
  `ext`         json          NULL,
  `is_deleted`  tinyint(1)    NOT NULL DEFAULT 0,
  `deleted_at`  datetime      NULL,
  `deleted_by`  bigint        NULL,
  PRIMARY KEY (`id`),
  KEY `idx_change_item_change` (`change_id`, `is_deleted`),
  KEY `idx_change_item_item` (`item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='变更明细增量 diff（V220）';

-- ========== 3. project_change 审批流程定义（ProcessDefAutoDeployRunner 启动自动部署） ==========
-- 两节点：主管审批（首节点不可驳回）→ 经理审批（终审可驳回）。
-- 变更金额属敏感操作，比立项少一节点（无会签），但保留两级复核。
INSERT INTO `sys_process_def`
    (process_code, process_name, flowable_deployment_id, def_version, status, node_list, remark, tenant_id)
VALUES
    ('project_change', '合同变更审批', NULL, 1, 'ENABLED',
     '[
       {"seq":1,"nodeId":"node_1","name":"主管审批","type":"ROLE","assignee":"ROLE_SUPERVISOR","rejectable":false},
       {"seq":2,"nodeId":"node_2","name":"经理审批","type":"ROLE","assignee":"ROLE_SALES_MANAGER","rejectable":true}
     ]',
     'V220 种子：合同变更两级 ROLE 审批（主管→经理）。末节点通过→applyChange 增量应用金额与明细。',
     0)
ON DUPLICATE KEY UPDATE
    process_name = VALUES(process_name),
    status       = VALUES(status),
    node_list    = VALUES(node_list),
    remark       = VALUES(remark);

-- ========== 4. 通知模板 4 个（变量对齐 ChangeServiceImpl 的 NotifyCommand.vars） ==========
-- CHANGE_SUBMITTED  {{changeNo}} {{projectName}} {{applicant}} {{amountDetail}}
-- CHANGE_APPROVED   {{changeNo}} {{projectName}} {{amountDetail}}
-- CHANGE_REJECTED   {{changeNo}} {{projectName}} {{approver}} {{reason}}
-- CHANGE_WITHDRAWN  {{changeNo}} {{projectName}} {{operator}}
INSERT INTO `sys_notification_template`
    (`code`, `type`, `template_name`, `title_tpl`, `content_tpl`, `enabled`, `tenant_id`) VALUES
('CHANGE_SUBMITTED',  'CHANGE_SUBMITTED',  '合同变更待审批',
 '合同变更待审批：{{changeNo}}',
 '{{applicant}} 发起合同变更 {{changeNo}}（{{projectName}}，{{amountDetail}}），请及时审批。', 1, 0),
('CHANGE_APPROVED',   'CHANGE_APPROVED',   '合同变更已通过',
 '合同变更已通过：{{changeNo}}',
 '您的合同变更 {{changeNo}}（{{projectName}}，{{amountDetail}}）已审批通过，变更已应用。', 1, 0),
('CHANGE_REJECTED',   'CHANGE_REJECTED',   '合同变更被驳回',
 '合同变更被驳回：{{changeNo}}',
 '{{approver}} 驳回了合同变更 {{changeNo}}（{{projectName}}）。理由：{{reason}}', 1, 0),
('CHANGE_WITHDRAWN',  'CHANGE_WITHDRAWN',  '合同变更已撤销',
 '合同变更已撤销：{{changeNo}}',
 '{{operator}} 撤销了合同变更 {{changeNo}}（{{projectName}}），审批流程已终止。', 1, 0)
ON DUPLICATE KEY UPDATE
    `type`          = VALUES(`type`),
    `template_name` = VALUES(`template_name`),
    `title_tpl`     = VALUES(`title_tpl`),
    `content_tpl`   = VALUES(`content_tpl`),
    `enabled`       = VALUES(`enabled`);

-- ========== 5. 权限点：合同变更发起/审批入口 ==========
-- 复用 project 菜单，不新增菜单；仅加一个操作权限点控制「发起变更」按钮。
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`)
VALUES ('project:change', '发起合同变更', 'BUTTON', 'EXECUTE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `status` = VALUES(`status`);

-- 挂到「项目列表」菜单下作为按钮（前端 v-has-perm="'project:change'" 显隐）；
-- 参照 V81 范式：固定 id + INSERT SELECT，sys_menu 无唯一键故用 id 幂等（先删后插）。
DELETE FROM `sys_menu` WHERE `id` = 330 AND `tenant_id` = 0;
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`)
SELECT 330, '发起合同变更', m.id, '', NULL, 'project:change', 'BUTTON', 4, 1, 'ENABLED', 0
FROM `sys_menu` m WHERE m.component = 'project/list' AND m.menu_type = 'MENU' AND m.tenant_id = 0 LIMIT 1;
