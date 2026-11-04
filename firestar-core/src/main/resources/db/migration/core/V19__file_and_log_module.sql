-- ============================================================
-- V19：文件管理 / 日志管理 模块落地
--
-- 背景（虎哥 2026-10-15 三轮对接定案）：
--   ① 文件存储接入阿里云 OSS：test/pre 共用 fire-star-stage，prod 用 fire-star-prod，
--      两桶均在北京；dev 走本地磁盘不上云。
--   ② Bucket 在北京、ECS 在张家口 → 跨地域。阿里云规则「跨地域访问 OSS 需通过公网或
--      云企业网 CEN，内网 Endpoint 跨地域不可用」，故内网免费通道用不了。
--      代价核算：上传流量一律免费（中转增量成本 0）；下载若中转则付两次流出费
--      （OSS 外网流出 → ECS，再 ECS 出方向 → 用户），故下载统一走后端签发的时效签名
--      URL，浏览器直连 OSS，只付一次。
--   ③ 自定义域名 oss.snrthn.com（已备案）：2025-03-20 起新开通 OSS 的中国内地 Bucket
--      不能用默认外网域名调用数据类 API，且通过默认域名访问图片/HTML 会被浏览器强制
--      下载而无法预览 —— 两者都要靠绑定自定义域名解决。
--   ④ 菜单调整：文件管理升为一级目录（下挂 文件列表 / 下载记录）；
--      新增日志管理一级目录（下挂 登录日志 / 操作日志）。
--
-- 幂等：DDL 用 information_schema 判存在再 ALTER（同 V11/V16）；
--       种子 INSERT 走 ON DUPLICATE KEY（同 V2/V4 风格）；UPDATE 重复执行结果一致。
-- 依赖：V1（sys_file_storage / sys_audit_log / sys_menu / sys_permission / sys_dict_* 已建）
-- ⚠️ 红线：DDL / 实体（FileStorage.java）/ 建表方案.md 三处必须同步对齐。
-- ============================================================

SET @db = DATABASE();

-- ---------------------------------------------------------------------------
-- 1. sys_file_storage 补列（OSS 多环境必需）
--    storage_path 语义不变（磁盘路径或 OSS objectKey），新增列用于区分存储位置、
--    追溯来源桶、保存展示名与校验值。
-- ---------------------------------------------------------------------------

SET @has_col = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'sys_file_storage' AND column_name = 'storage_type');
SET @sql = IF(@has_col = 0,
  'ALTER TABLE `sys_file_storage` ADD COLUMN `storage_type` VARCHAR(16) NOT NULL DEFAULT ''LOCAL'' COMMENT ''存储类型：LOCAL 本地磁盘 / OSS 阿里云对象存储'' AFTER `storage_path`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'sys_file_storage' AND column_name = 'bucket_name');
SET @sql = IF(@has_col = 0,
  'ALTER TABLE `sys_file_storage` ADD COLUMN `bucket_name` VARCHAR(64) NULL COMMENT ''OSS Bucket 名（test/pre 共用 stage，prod 用 prod）'' AFTER `storage_type`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'sys_file_storage' AND column_name = 'original_name');
SET @sql = IF(@has_col = 0,
  'ALTER TABLE `sys_file_storage` ADD COLUMN `original_name` VARCHAR(255) NULL COMMENT ''原始文件名（仅展示与命名规范溯源，绝不拼进 objectKey，防路径穿越）'' AFTER `file_name`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'sys_file_storage' AND column_name = 'file_hash');
SET @sql = IF(@has_col = 0,
  'ALTER TABLE `sys_file_storage` ADD COLUMN `file_hash` VARCHAR(64) NULL COMMENT ''文件内容 SHA-256（完整性校验 / 去重）'' AFTER `file_size`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'sys_file_storage' AND column_name = 'status');
SET @sql = IF(@has_col = 0,
  'ALTER TABLE `sys_file_storage` ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT ''NORMAL'' COMMENT ''状态（字典 file_status）：NORMAL 正常 / ARCHIVED 已归档'' AFTER `file_hash`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'sys_file_storage' AND column_name = 'download_count');
SET @sql = IF(@has_col = 0,
  'ALTER TABLE `sys_file_storage` ADD COLUMN `download_count` INT NOT NULL DEFAULT 0 COMMENT ''下载次数（冗余计数，避免列表页联表 count）'' AFTER `status`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2. sys_login_log 登录日志（需求 V1.2「记录登录日志和操作日志」）
--    与 sys_audit_log 的区别：操作日志（审计）永久保留且不可删改（红线 5）；
--    登录日志体量大、取证价值随时间衰减，可定期清理（建议保留 1 年）。
--    同审计日志：只增不删不改，故不带逻辑删除三字段。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_login_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `user_id`       BIGINT       NULL COMMENT '用户 ID（登录失败时可能为空）',
    `username`      VARCHAR(64)  NULL COMMENT '登录账号（冗余，用户被删后仍可追溯）',
    `login_status`  VARCHAR(16)  NOT NULL COMMENT '结果（字典 login_status）：SUCCESS/FAIL/KICKED_OUT',
    `ip_address`    VARCHAR(64)  NULL COMMENT '登录 IP',
    `user_agent`    VARCHAR(512) NULL COMMENT '浏览器 UA',
    `fail_reason`   VARCHAR(255) NULL COMMENT '失败原因（密码错误/账号禁用/被顶下线等）',
    `login_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（此表实际不使用）',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    KEY `idx_loginlog_user` (`tenant_id`, `user_id`),
    KEY `idx_loginlog_time` (`tenant_id`, `login_at`),
    KEY `idx_loginlog_status` (`tenant_id`, `login_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='登录日志（只增；可定期清理，与审计日志的永久保留区分）';

-- ---------------------------------------------------------------------------
-- 3. 字典类型与数据（登录结果 / 文件状态 / 存储类型）
-- ---------------------------------------------------------------------------
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('login_status',  '登录结果', 'ENABLED', '登录日志结果', 0),
('file_status',   '文件状态', 'ENABLED', '文件归档状态', 0),
('storage_type',  '存储类型', 'ENABLED', '文件存储位置', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`);

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`) VALUES
('login_status', '成功',     'SUCCESS',    1, 0, '启用', 0),
('login_status', '失败',     'FAIL',       2, 0, '启用', 0),
('login_status', '被顶下线', 'KICKED_OUT', 3, 0, '启用', 0),
('file_status',  '正常',     'NORMAL',     1, 1, '启用', 0),
('file_status',  '已归档',   'ARCHIVED',   2, 0, '启用', 0),
-- DELETED：财务凭证有合规保留期，删除只做标记，DB 记录与 OSS 对象都保留，
-- 便于追溯「谁在什么时候删了什么」。物理清理交给后续归档任务按保留期执行。
('file_status',  '已删除',   'DELETED',    3, 0, '启用', 0),
('storage_type', '本地磁盘', 'LOCAL',      1, 1, '启用', 0),
('storage_type', '对象存储', 'OSS',        2, 0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`);

-- ---------------------------------------------------------------------------
-- 4. 权限点：日志管理（模块级粗粒度，与 dict:manage / file:manage 同风格）
--    说明：菜单粒度 ≠ 权限粒度 —— 一级 CATALOG 不带权限点（同财务中心/系统管理），
--    两个子菜单共享 log:view，写操作才用 log:manage。
-- ---------------------------------------------------------------------------
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('log:view',   '日志查看', 'API', 'READ',  'ENABLED', 0),
('log:manage', '日志管理', 'API', 'WRITE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `status` = VALUES(`status`);

-- ---------------------------------------------------------------------------
-- 5. 菜单结构调整
--    一级排序现状：工作台(1) / 项目管理(2) / 财务中心(3) / 业务中心(4) / 运营中心(5) / 系统管理(11)
--    新增：文件管理(6) / 日志管理(7) —— 文件与日志是横切能力，不属于任何业务场景，
--          按 V6「按业务场景分组收纳」的同一口径，升为一级目录更合理。
--
--    id=18 复用为「文件列表」（V4 已建，改 parent/名称/路由/组件，其他角色已有授权不受影响）；
--    新增 25/27/28/29/30。id 水位：V7 用 22、V8 用 23、V10 用 24，故从 25 起。
-- ---------------------------------------------------------------------------
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`) VALUES
(25, '文件管理', 0,  '',                   '',                   NULL,        'CATALOG', 6, 1, 'ENABLED', 0),
(27, '下载记录', 25, '/file/download-log', 'file/download-log',  'file:view', 'MENU',    2, 1, 'ENABLED', 0),
(28, '日志管理', 0,  '',                   '',                   NULL,        'CATALOG', 7, 1, 'ENABLED', 0),
(29, '登录日志', 28, '/log/login',         'log/login',          'log:view',  'MENU',    1, 1, 'ENABLED', 0),
(30, '操作日志', 28, '/log/operation',     'log/operation',      'log:view',  'MENU',    2, 1, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`),
  `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`),
  `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`);

-- 原「文件管理」(18) 改造为「文件列表」，挂到新目录 25 下
-- （V8 曾单独设其 sort_no=7，那是系统管理目录内的排序，此处重置为组内 1）
UPDATE `sys_menu`
SET `menu_name`  = '文件列表',
    `parent_id`  = 25,
    `route_path` = '/file/list',
    `component`  = 'file/list',
    `perm_code`  = 'file:view',
    `sort_no`    = 1
WHERE `id` = 18;

-- ---------------------------------------------------------------------------
-- 6. 补授 ROLE_ADMIN —— 必须做，否则管理员看不到新菜单
--    ⚠️ V6 的血泪教训：V4 的授权语句只在执行时覆盖当时已存在的菜单（id<=18），
--       新增菜单若不补授，管理员侧栏根本不出现。V6 当年专门补了 19/20/21。
--    （id=18 已在 V4 授权，仅改 parent 不影响，无需重复补）
-- ---------------------------------------------------------------------------
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id IN (25, 27, 28, 29, 30)
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);

INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code IN ('log:view', 'log:manage')
ON DUPLICATE KEY UPDATE `status` = VALUES(`status`);
