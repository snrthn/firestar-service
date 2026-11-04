-- ============================================================================
-- V30：P2 通知 MVP — 2 张表 + 模板种子 + 菜单权限种子
-- 依据：docs/P2-通知MVP-实施方案.md（虎哥 2026-10-20 拍板：方案 C —— 只做通知中枢，
--       事件源留接口占位，管理端「手动发布」触发验收）
-- 依赖：V1(baseline) → V4(rbac 种子, 系统管理父菜单 id=12) → V13(icon 列)
--       → V25(P1 协议) → V26(id=25 冲突修复, 顺带确认 26 为空号)
--
-- ⚠️ 血案铁律逐条遵守：
-- ① 菜单 id 写死前已全量查库核对（2026-10-20 实查）：已占用 1–25 / 27–31，
--    **26 是历史跳过号**（V26 冲突修复后未回填）不复用；新菜单取 32/33 顺延最大值。
-- ② 系统管理(parent_id=12)下 sort_no 1–8 已占（8=参数设置 id=31）→ 新菜单取 9/10。
-- ③ ON DUPLICATE KEY UPDATE + 固定主键 = 原地改写风险（V25 血案）：32/33 两 id
--    经查库确认空闲，且本脚本只 INSERT 新行、不 UPDATE 任何既有行。
-- ④ ext JSON + 审计四件套齐全（V27 血案：BaseEntity 全字段必须进表，漏 ext 会
--    让全表 SELECT 报 Unknown column 'ext' 而编译期不报）。
-- ⑤ 模板表不设 version 字符串列 —— 与 BaseEntity 乐观锁 version(INT) 撞名（V25 血案同型）。
--
-- 幂等性说明（为何不用 V27 的 procedure 守卫）：V27 的 procedure 是为 ADD COLUMN
-- 场景服务的（MySQL DDL 无 IF NOT EXISTS）。本脚本全是 CREATE TABLE IF NOT EXISTS
-- + INSERT ... ON DUPLICATE KEY UPDATE，原生即幂等；手动 source 预跑后再由 Flyway
-- 补记录也不会重复报错（V26 同款路径实测过）。
--
-- 同步项（必须同版本改代码，见 DatabaseCheckService 注释）：
--   EXPECTED_TABLE_COUNT 31 → 33（+sys_notification +sys_notification_template）
-- ============================================================================

-- ========== 1. 通知事实表：一人一条，只插 + 更新 read_at，不删 ==========
CREATE TABLE IF NOT EXISTS `sys_notification` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT       NOT NULL DEFAULT 0,
  `version`      INT          NOT NULL DEFAULT 0,   -- 乐观锁（BaseEntity）
  `receiver_id`  BIGINT       NOT NULL,
  `type`         VARCHAR(32)  NOT NULL,             -- APPROVAL_TODO / SYSTEM_NOTICE ...
  `title`        VARCHAR(128) NOT NULL,
  `content`      VARCHAR(1024) NOT NULL DEFAULT '',
  `biz_type`     VARCHAR(32)  NULL,
  `biz_id`       VARCHAR(64)  NULL,
  `link_url`     VARCHAR(255) NULL,                 -- 点击跳转（相对路径）
  `level`        VARCHAR(16)  NOT NULL DEFAULT 'INFO', -- INFO / WARN / URGENT
  `read_at`      DATETIME     NULL,
  `read_channel` VARCHAR(16)  NULL,                 -- INAPP
  `dedup_key`    VARCHAR(128) NOT NULL,             -- 唯一键：重复触发不落第二条
  `expire_at`    DATETIME     NULL,
  `ext`          JSON         NULL,                 -- ⚠️ ext 铁律（V27 血案）
  `created_by`   VARCHAR(64)  NULL,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`   VARCHAR(64)  NULL,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dedup` (`dedup_key`),
  KEY `idx_receiver_read_created` (`receiver_id`, `read_at`, `created_at`),
  KEY `idx_receiver_type_created` (`receiver_id`, `type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知事实表';

-- ========== 2. 通知模板表：管理员可改文案；无 version 字符串列（与乐观锁撞名） ==========
CREATE TABLE IF NOT EXISTS `sys_notification_template` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT       NOT NULL DEFAULT 0,
  `version`     INT          NOT NULL DEFAULT 0,   -- 乐观锁（BaseEntity）
  `code`        VARCHAR(32)  NOT NULL,             -- NotifyCommand.code 对应
  `type`        VARCHAR(32)  NOT NULL,
  `title_tpl`   VARCHAR(255) NOT NULL,
  `content_tpl` TEXT         NOT NULL,             -- {{变量}} 占位
  `enabled`     TINYINT      NOT NULL DEFAULT 1,
  `ext`         JSON         NULL,                 -- ⚠️ ext 铁律
  `created_by`  VARCHAR(64)  NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`  VARCHAR(64)  NULL,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tpl_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知模板表';

-- ========== 3. 模板种子（7 类，文案占位、管理端可改） ==========
-- P2 阶段只有 SYSTEM_NOTICE 真正被管理端发布用到；其余先种着，等业务接入即生效。
-- ⚠️ 变量名与 NotificationService 渲染模型对齐（缺失变量留空并 warn，不抛）：
--    APPROVAL_TODO   {{bizTitle}} {{applicant}}
--    APPROVAL_RESULT {{bizTitle}} {{result}} {{approver}} {{comment}}
--    RECEIPT_AUDIT   {{applicant}} {{amount}}
--    INVOICE_DONE    {{bizTitle}} {{invoiceNo}}
--    BUDGET_ALERT    {{budgetName}} {{usedRate}}
--    LOAN_OVERDUE    {{amount}} {{overdueDays}}
--    SYSTEM_NOTICE   {{title}} {{content}}
INSERT INTO `sys_notification_template` (`code`, `type`, `title_tpl`, `content_tpl`, `enabled`, `tenant_id`) VALUES
('APPROVAL_TODO',   'APPROVAL_TODO',   '您有一条待审批：{{bizTitle}}',            '{{applicant}} 提交的「{{bizTitle}}」等待您审批，请及时处理。', 1, 0),
('APPROVAL_RESULT', 'APPROVAL_RESULT', '审批结果：{{bizTitle}}',                  '您提交的「{{bizTitle}}」审批{{result}}。审批人：{{approver}}。意见：{{comment}}', 1, 0),
('RECEIPT_AUDIT',   'RECEIPT_AUDIT',   '回款认领待审核',                          '{{applicant}} 提交的回款认领（{{amount}}）等待审核，请及时处理。', 1, 0),
('INVOICE_DONE',    'INVOICE_DONE',    '发票开具完成：{{bizTitle}}',              '「{{bizTitle}}」的发票已开具，发票号：{{invoiceNo}}。', 1, 0),
('BUDGET_ALERT',    'BUDGET_ALERT',    '预算预警：{{budgetName}}',                '「{{budgetName}}」已使用 {{usedRate}}，超出预警阈值，请关注。', 1, 0),
('LOAN_OVERDUE',    'LOAN_OVERDUE',    '借款逾期提醒',                            '您的借款（{{amount}}）已逾期 {{overdueDays}} 天，请尽快处理。', 1, 0),
('SYSTEM_NOTICE',   'SYSTEM_NOTICE',   '{{title}}',                              '{{content}}', 1, 0)
ON DUPLICATE KEY UPDATE `type` = VALUES(`type`), `title_tpl` = VALUES(`title_tpl`), `content_tpl` = VALUES(`content_tpl`), `enabled` = VALUES(`enabled`);

-- ========== 4. 权限点 2 个（action_type 取值同 V25 的 config:manage：WRITE） ==========
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('notify:manage',   '通知记录管理', 'API', 'WRITE', 'ENABLED', 0),
('notify:template', '通知模板管理', 'API', 'WRITE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`), `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- ========== 5. 菜单 2 个（系统管理 id=12 下，sort_no=9/10 顺延现有 1–8） ==========
-- 用户侧（铃铛/面板）不走 sys_menu：全登录用户功能，builtinPages 本地常量路由模式
-- （同 /profile，见 P2 方案 4.4 路由归属决策）。此处仅管理侧两个页面。
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(32, '通知模板', 12, '/system/notify-template', 'system/notify-template', 'notify:template', 'MENU', 9,  1, 'ENABLED', 'Notification', 0),
(33, '通知记录', 12, '/system/notify-record',   'system/notify-record',   'notify:manage',   'MENU', 10, 1, 'ENABLED', 'Bell',        0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`), `icon` = VALUES(`icon`);

-- ========== 6. ROLE_ADMIN 授权（权限点 + 菜单，照 V25 范式） ==========
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code IN ('notify:manage', 'notify:template')
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id IN (32, 33)
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
