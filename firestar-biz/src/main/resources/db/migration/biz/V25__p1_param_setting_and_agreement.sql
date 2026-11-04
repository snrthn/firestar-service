-- ============================================================================
-- V25：P1 参数设置 + 协议（3 张协议表 + 参数设置结构化 config + 菜单权限）
-- 依赖：V1(baseline) → V4(rbac 种子, 系统管理父菜单 id=12) → V10(岗位菜单 id=24)
-- 内容：
--   ① 协议三表 sys_agreement / sys_agreement_version / sys_agreement_consent
--   ② 参数设置结构化 config key（SYSTEM 类，与 V2 已有 5 条不冲突）
--   ③ 权限点 config:manage + 参数设置菜单(id=25, 系统管理下) + ROLE_ADMIN 授权
-- 幂等：CREATE TABLE IF NOT EXISTS；INSERT 均 ON DUPLICATE KEY UPDATE；重复执行结果一致。
--   （本文件一经提交永不修改内容，后续变更走 V{n} 增量脚本）
-- ============================================================================

-- ========== 1. 协议三表 ==========
-- 对齐 V10 sys_post 范式：审计四件套(created_by/created_at/updated_by/updated_at) + version 乐观锁 + tenant_id
CREATE TABLE IF NOT EXISTS `sys_agreement` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `code`               VARCHAR(32)  NOT NULL COMMENT '协议编码 TERMS/PRIVACY',
    `name`               VARCHAR(64)  NOT NULL COMMENT '协议名称',
    `scope`              VARCHAR(32)  NOT NULL DEFAULT 'PLATFORM' COMMENT '作用域 PLATFORM/TENANT',
    `status`             VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status）',
    `current_version_id` BIGINT       NULL     COMMENT '当前生效版本 → sys_agreement_version.id（NULL=取最新已发布版本）',
    `tenant_id`          BIGINT       NOT NULL DEFAULT 0,
    `created_by`         BIGINT       NULL,
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_by`         BIGINT       NULL,
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `version`            INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_code` (`tenant_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='协议主体（一类型一条）';

-- 只增不改：每次发版插入新行，永不 UPDATE content
CREATE TABLE IF NOT EXISTS `sys_agreement_version` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `agreement_id`    BIGINT       NOT NULL COMMENT '→ sys_agreement.id',
    `ver`             VARCHAR(16)  NOT NULL COMMENT 'v1/v2...（版本号，区别于乐观锁列 version）',
    `content`         MEDIUMTEXT   NOT NULL COMMENT 'Markdown 快照（只增不改，合规可溯源）',
    `change_summary`  VARCHAR(512) NULL     COMMENT '变更摘要（首读/升级弹层左侧展示）',
    `effective_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效时间',
    `need_reconsent`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否要求已同意用户重新同意（存量全量弹协议靠此标记，首次靠无记录）',
    `published_by`    BIGINT       NULL     COMMENT '发布人 → sys_user.id',
    `status`          VARCHAR(32)  NOT NULL DEFAULT 'PUBLISHED' COMMENT '状态（字典 common_status）',
    `tenant_id`       BIGINT       NOT NULL DEFAULT 0,
    `created_by`      BIGINT       NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_by`      BIGINT       NULL,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `version`         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agr_ver` (`agreement_id`, `ver`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='协议版本（只增不改）';

-- 只增不删：撤回写 WITHDRAW 新行，不删旧 AGREE 行
CREATE TABLE IF NOT EXISTS `sys_agreement_consent` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`      BIGINT       NOT NULL COMMENT '→ sys_user.id',
    `agreement_id` BIGINT       NOT NULL,
    `version_id`   BIGINT       NOT NULL COMMENT '→ sys_agreement_version.id（存 id 不存字符串，三年后仍能原样复现当时同意的文本）',
    `action`       VARCHAR(16)  NOT NULL COMMENT 'AGREE/WITHDRAW',
    `channel`      VARCHAR(16)  NOT NULL DEFAULT 'LOGIN' COMMENT 'LOGIN/SETTINGS',
    `ip`           VARCHAR(64)  NULL,
    `user_agent`   VARCHAR(512) NULL,
    `tenant_id`    BIGINT       NOT NULL DEFAULT 0,
    `created_by`   BIGINT       NULL,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_by`   BIGINT       NULL,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `version`      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_user_agr` (`user_id`, `agreement_id`, `version_id`),
    KEY `idx_agr_ver` (`agreement_id`, `version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='协议同意证据（只增不删）';

-- ========== 2. 协议种子（占位内容，运营上线前替换为正式文本） ==========
-- 首次发布 v1：need_reconsent=0（首次弹层由"无同意记录"触发，不靠重同意标记）
INSERT INTO `sys_agreement` (`code`, `name`, `scope`, `status`, `tenant_id`) VALUES
('TERMS',   '用户协议', 'PLATFORM', 'ENABLED', 0),
('PRIVACY', '隐私政策', 'PLATFORM', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `status` = VALUES(`status`);

INSERT INTO `sys_agreement_version` (`agreement_id`, `ver`, `content`, `change_summary`, `effective_at`, `need_reconsent`, `status`, `tenant_id`)
SELECT a.id, 'v1', '# 用户协议\n\n（占位内容，运营上线前请替换为正式文本）', '初次发布', NOW(), 0, 'PUBLISHED', 0
FROM `sys_agreement` a WHERE a.code = 'TERMS'
ON DUPLICATE KEY UPDATE `content` = VALUES(`content`), `change_summary` = VALUES(`change_summary`);

INSERT INTO `sys_agreement_version` (`agreement_id`, `ver`, `content`, `change_summary`, `effective_at`, `need_reconsent`, `status`, `tenant_id`)
SELECT a.id, 'v1', '# 隐私政策\n\n（占位内容，运营上线前请替换为正式文本）', '初次发布', NOW(), 0, 'PUBLISHED', 0
FROM `sys_agreement` a WHERE a.code = 'PRIVACY'
ON DUPLICATE KEY UPDATE `content` = VALUES(`content`), `change_summary` = VALUES(`change_summary`);

-- ========== 3. 参数设置结构化 config key（SYSTEM 类，与 V2 已有 5 条不冲突） ==========
-- 关于我们弹窗复用：system_name / icp_no / support_contact / open_source_notice / app_version
-- 基本信息 Tab：logo_url / login_notice / notify_global_switch
INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `remark`, `tenant_id`) VALUES
('system_name',         '火星财务',   'SYSTEM', '系统名称（登录页/关于我们展示）', 0),
('logo_url',            '',           'SYSTEM', '系统 Logo 地址', 0),
('icp_no',              '',           'SYSTEM', 'ICP 备案号（关于我们弹窗展示）', 0),
('support_contact',     'support@marsfn.com', 'SYSTEM', '技术支持联系方式', 0),
('login_notice',        '',           'SYSTEM', '登录页公告', 0),
('notify_global_switch','true',       'SYSTEM', '通知总开关', 0),
('open_source_notice',  '本项目基于 Spring Boot / Vue 3 / Element Plus 等开源组件构建', 'SYSTEM', '开源组件声明（关于我们弹窗）', 0),
('app_version',         '0.0.1',      'SYSTEM', '应用版本号（关于我们弹窗展示）', 0)
ON DUPLICATE KEY UPDATE `config_value` = VALUES(`config_value`), `remark` = VALUES(`remark`), `config_type` = VALUES(`config_type`);

-- ========== 4. 权限点 config:manage ==========
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('config:manage', '参数设置', 'API', 'WRITE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`), `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- ========== 5. 参数设置菜单（系统管理 id=12 下，sort_no=7 排在文件管理 id=18 之后） ==========
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`) VALUES
(25, '参数设置', 12, '/system/param-setting', 'system/param-setting', 'config:manage', 'MENU', 7, 1, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`);

-- ========== 6. ROLE_ADMIN 授权（权限点 + 菜单） ==========
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code = 'config:manage'
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, 25, 0
FROM `sys_role` r WHERE r.role_code = 'ROLE_ADMIN'
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
