-- ============================================================================
-- V32：P3 问题反馈 + 帮助中心（虎哥 2026-10-20 拍板）
--   1. sys_feedback / sys_feedback_message / sys_help_article 三张表
--      （前缀必须 sys_：DatabaseCheckService.BUSINESS_TABLE_PREFIXES 只认
--        fin_/sys_，用其他前缀会被 db-check 判为「基线之外的表」误报）
--   2. 字典 feedback_status（待处理/处理中/已解决/已关闭）
--   3. 权限点 2 个 + 隐藏菜单 3 个（visible=0，侧栏不展示、路由照常注册）
--   4. FAQ 10 条占位种子（虎哥：直接起草不用审）
-- 依赖：V1 → V2(字典) → V4(系统管理 parent_id=12) → V30/31(id 32/33 已占)
--
-- ⚠️ 血案铁律逐条遵守（同 V30 头注）：
-- ① 菜单 id 写死前核对：已占用 1–25 / 27–33，26 历史跳号不复用 → 新菜单 34/35/36。
-- ② 系统管理(id=12)下 sort_no 1–10 已占 → 新菜单取 11/12/13。
-- ③ 只 INSERT 新行 + ON DUPLICATE KEY UPDATE，不 UPDATE 既有行。
-- ④ ext JSON + tenant_id + version + 审计四件套齐全（V27 血案）。
-- ⑤ 表前缀 sys_（见第 1 节注释）。
--
-- 幂等性：全脚本 CREATE TABLE IF NOT EXISTS + INSERT ... ON DUPLICATE KEY UPDATE，
-- 原生幂等（V30 同款路径）。
--
-- 同步项（必须同版本改代码，见 DatabaseCheckService 注释）：
--   EXPECTED_TABLE_COUNT 33 → 36（+sys_feedback +sys_feedback_message +sys_help_article）
-- ============================================================================

-- ========== 1. 反馈主表 ==========
CREATE TABLE IF NOT EXISTS `sys_feedback` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT        NOT NULL DEFAULT 0,
  `version`     INT           NOT NULL DEFAULT 0,   -- 乐观锁（BaseEntity）
  `title`       VARCHAR(100)  NOT NULL,
  `content`     MEDIUMTEXT    NOT NULL,             -- 富文本 HTML（Tiptap 产物，前端 RichView 渲染）
  `receiver_id` BIGINT        NOT NULL,             -- 指定接收人 → sys_user.id（全员可选）
  `status`      VARCHAR(20)   NOT NULL DEFAULT 'PENDING', -- 字典 feedback_status
  `ext`         JSON          NULL,                 -- ⚠️ ext 铁律（V27 血案）
  `created_by`  VARCHAR(64)   NULL,
  `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`  VARCHAR(64)   NULL,
  `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_receiver_status` (`receiver_id`, `status`, `id`),
  KEY `idx_creator_status` (`created_by`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问题反馈主表';

-- ========== 2. 反馈留言表（只增不改不删，无更新语义但继承审计四件套保持全表一致） ==========
CREATE TABLE IF NOT EXISTS `sys_feedback_message` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT        NOT NULL DEFAULT 0,
  `version`     INT           NOT NULL DEFAULT 0,
  `feedback_id` BIGINT        NOT NULL,             -- → sys_feedback.id
  `sender_id`   BIGINT        NOT NULL,             -- → sys_user.id（创建人/接收人双向留言）
  `content`     MEDIUMTEXT    NOT NULL,
  `ext`         JSON          NULL,
  `created_by`  VARCHAR(64)   NULL,
  `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`  VARCHAR(64)   NULL,
  `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_feedback_created` (`feedback_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问题反馈留言表';

-- ========== 3. 帮助中心文章表 ==========
CREATE TABLE IF NOT EXISTS `sys_help_article` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT        NOT NULL DEFAULT 0,
  `version`     INT           NOT NULL DEFAULT 0,
  `title`       VARCHAR(100)  NOT NULL,
  `content`     MEDIUMTEXT    NOT NULL,             -- 富文本 HTML
  `category`    VARCHAR(30)   NOT NULL DEFAULT '通用', -- 分类（阅读页左侧锚点分组）
  `sort_no`     INT           NOT NULL DEFAULT 0,
  `status`      VARCHAR(16)   NOT NULL DEFAULT 'ENABLED', -- 字典 common_status
  `ext`         JSON          NULL,
  `created_by`  VARCHAR(64)   NULL,
  `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`  VARCHAR(64)   NULL,
  `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_sort` (`status`, `sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帮助中心文章表';

-- ========== 4. 字典：反馈状态 ==========
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('feedback_status', '反馈状态', '启用', '问题反馈生命周期：待处理/处理中/已解决/已关闭', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`);

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `style`, `is_default`, `status`, `tenant_id`) VALUES
('feedback_status', '待处理', 'PENDING',    1, 'warning', 1, '启用', 0),
('feedback_status', '处理中', 'PROCESSING', 2, 'primary', 0, '启用', 0),
('feedback_status', '已解决', 'RESOLVED',   3, 'success', 0, '启用', 0),
('feedback_status', '已关闭', 'CLOSED',     4, 'info',    0, '启用', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `style` = VALUES(`style`), `is_default` = VALUES(`is_default`), `status` = VALUES(`status`);

-- ========== 5. 权限点 ==========
-- feedback:list：反馈页面访问（全员功能，授权给所有角色，见第 7 节）
-- system:help：帮助中心维护（管理端）
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('feedback:list', '问题反馈',     'API', 'WRITE', 'ENABLED', 0),
('system:help',   '帮助中心维护', 'API', 'WRITE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`), `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- ========== 6. 隐藏菜单 3 个（系统管理 id=12 下，visible=0 侧栏不展示、路由照常注册） ==========
-- 34 问题反馈（全员） 35 帮助中心维护（管理员） 36 帮助中心阅读页（全员）
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(34, '问题反馈',     12, '/system/feedback', 'system/feedback',     'feedback:list', 'MENU', 11, 0, 'ENABLED', 'ChatDotRound', 0),
(35, '帮助中心维护', 12, '/system/help-manage', 'system/help-manage', 'system:help', 'MENU', 12, 0, 'ENABLED', 'QuestionFilled', 0),
(36, '帮助中心',     12, '/help',            'help/index',          NULL,            'MENU', 13, 0, 'ENABLED', 'Reading',      0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`), `icon` = VALUES(`icon`);

-- ========== 7. 角色授权 ==========
-- 7.1 管理员：三个菜单 + 两个权限点全给（照 V25/V30 范式）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code IN ('feedback:list', 'system:help')
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id IN (34, 35, 36)
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);

-- 7.2 非管理员角色：问题反馈 + 帮助中心阅读页是全员功能（header 入口，未授权会落 403），
--     权限点 feedback:list 也一并授予；帮助中心维护(35)与 system:help 不授。
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code <> 'ROLE_ADMIN' AND p.perm_code = 'feedback:list'
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code <> 'ROLE_ADMIN' AND m.id IN (34, 36)
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);

-- ========== 8. FAQ 占位种子（10 条，虎哥：直接起草不用审，管理端可改） ==========
INSERT INTO `sys_help_article` (`title`, `content`, `category`, `sort_no`, `status`, `tenant_id`) VALUES
('忘记密码怎么办',
 '<p>请联系系统管理员在【系统管理 → 用户管理】中重置密码。重置后首次登录会要求修改密码，请及时设置新密码并妥善保管。</p>', '账号与登录', 1, 'ENABLED', 0),
('登录后提示无权限或页面 403',
 '<p>菜单和按钮权限由管理员在【系统管理 → 角色管理】中分配。若您看不到某个菜单或访问页面提示 403，请联系管理员确认您的角色是否已勾选对应菜单与权限。</p>', '账号与登录', 2, 'ENABLED', 0),
('如何在多个设备上登录',
 '<p>系统为单点登录模式：同一账号在新设备登录后，原设备会话会立即失效并弹出重新登录提示。请勿多设备共用同一账号。</p>', '账号与登录', 3, 'ENABLED', 0),
('侧边栏看不到某个菜单',
 '<p>侧边栏只展示「已授权且已启用且可见」的菜单。常见原因：① 角色未分配该菜单；② 菜单被设置为隐藏（如问题反馈、帮助中心等，请从头像下拉菜单进入）；③ 菜单被停用。</p>', '系统使用', 4, 'ENABLED', 0),
('如何提交问题反馈',
 '<p>点击右上角头像 →「问题反馈」，新建反馈时可用富文本描述问题（支持截图粘贴），并选择一位接收人。提交后可在「我提交的」中查看处理进度并继续留言沟通。</p>', '系统使用', 5, 'ENABLED', 0),
('反馈提交后多久会有响应',
 '<p>反馈提交后会实时推送通知给接收人（在线时秒级送达）。您可以在「问题反馈 → 我提交的」中随时查看状态变化（待处理 → 处理中 → 已解决 → 已关闭）。</p>', '系统使用', 6, 'ENABLED', 0),
('通知铃铛不显示新消息',
 '<p>实时通知依赖 SSE 长连接，若网络波动导致掉线，系统会自动指数退避重连并校正未读数。若长时间未恢复，请刷新页面重新建立连接。</p>', '系统使用', 7, 'ENABLED', 0),
('富文本里如何插入图片',
 '<p>编辑器支持直接粘贴截图或点击图片按钮上传，图片会存入系统文件服务。上传后可拖拽选中外侧圆点调整图片大小。</p>', '常见操作', 8, 'ENABLED', 0),
('附件上传失败怎么办',
 '<p>请确认文件大小在限制范围内且网络正常。若使用 OSS 存储，上传失败通常为临时网络问题，可稍后重试；多次失败请联系管理员检查存储配置。</p>', '常见操作', 9, 'ENABLED', 0),
('在哪里查看系统版本信息',
 '<p>点击右上角头像 →「关于我们」，弹窗中展示系统名称、版本号、技术支持联系方式与 ICP 备案号。</p>', '常见操作', 10, 'ENABLED', 0);
