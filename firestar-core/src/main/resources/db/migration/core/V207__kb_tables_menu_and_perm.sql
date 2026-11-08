-- ============================================================================
-- V207：知识库 P0 三表 + AI中心「知识库」菜单 / BUTTON / 权限点（2026-11-09）
--
-- 背景（设计见 docs/知识库模块设计.md §5 / §6 / §8 步骤 3）：
--   V206 只落了字典（file_type 收窄、kb_doc 业务归属、kb_attachment_role 4 项），
--   本迁移建真正的存储层与菜单权限，管理端挂「AI中心」(id=95) 下。
--   帮助中心 sys_help_article 原地不动，后期由 HelpArticleSource 只读接入。
--
-- ⚠️ 前置条件：MySQL >= 5.7.6（ngram parser 为内置 server plugin，启动自动加载）。
--    已由虎哥确认线上 5.7.22；ngram_token_size 默认 2，单字查询命中不了，
--    BOOLEAN MODE 规避 50% 阈值。参数调整属部署动作，本脚本不碰。
--
-- ⚠️ DatabaseCheckService 计数：本迁移新增 3 张 sys_ 表 → 48 → 51。
--    dict_type 36 / dict_data 154 不变（V206 已落字典，本迁移不再动字典）。
--    漏改会 /actuator/health DOWN → 503（V19/V20/V22 三次同款事故）。
--
-- ⚠️ 菜单两条种子铁律（SeedIntegrityCheck 规则，V108 / V107 两次事故）：
--    ① route_path 必须带前导 '/'（缺了从 /system/* 页点击会相对解析成 403）；
--    ② 带 perm_code 的 MENU 必须有子 BUTTON 节点（否则前端 v-has-perm 全掉）。
--    component='system/knowledge' 当前无对应 .vue：前端 resolveView 有
--    PagePlaceholder 兜底（带 title/path/perm 上下文），不会白屏；步骤 4 落编辑器时
--    新建 src/views/system/knowledge.vue 即自动接管，无需改菜单。
--
-- 依赖：V1（sys_menu/sys_permission/sys_role*）/ V103（AI中心 95）
--       / V206（kb_attachment_role 字典、kb_doc 业务归属）。
-- 幂等：CREATE TABLE IF NOT EXISTS + 全部 INSERT 带 ON DUPLICATE KEY UPDATE。
-- id 核对：9503 / 95031~95034 经全量 migration grep 确认空闲（V206 注释里出现过
--        「9503」但仅是说明文字，未落库）。
-- ============================================================================

-- ========== 1. 知识库文档主表 ==========
-- status：DRAFT / PENDING_REVIEW / PUBLISHED / ARCHIVED
--   PENDING_REVIEW 为「接 Flowable 审核」预留位，当前流程永不进入，后期不改枚举、不迁移。
-- acl_roles：可见角色标签（逗号分隔），检索器按当前用户身份过滤；frontmatter 不覆盖本字段。
-- egress_allowed：内容级出域准入预留位，P0 默认 1 且不参与判定（AllowAllKbEgressPolicy）。
CREATE TABLE IF NOT EXISTS `sys_kb_doc` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `title`          VARCHAR(256) NOT NULL                COMMENT '文档标题（MD 内唯一 H1 与之对齐）',
  `summary`        VARCHAR(512) DEFAULT NULL            COMMENT '摘要 / 前置上下文（chunk 拼接用）',
  `content_md`     LONGTEXT     DEFAULT NULL            COMMENT 'Markdown 正文（图片只允许 file:<fileId> 协议）',
  `acl_roles`      VARCHAR(512) DEFAULT NULL            COMMENT '可见角色标签，逗号分隔；NULL=按后端默认策略',
  `status`         VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT|PENDING_REVIEW|PUBLISHED|ARCHIVED（Java enum，不占字典行）',
  `version`        INT          NOT NULL DEFAULT 1      COMMENT '当前生效版本；发布 v+1 时旧版本 chunk 置失效',
  `source_type`    VARCHAR(32)  NOT NULL DEFAULT 'MD'   COMMENT 'MD|HELP_ARTICLE|FILE|URL（Java enum）',
  `source_ref`     VARCHAR(512) DEFAULT NULL            COMMENT '外部源定位符（help_article.id / URL 等）',
  `egress_allowed` TINYINT      NOT NULL DEFAULT 1      COMMENT '内容级出域开关（预留位，P0 恒允许）',
  `published_at`   DATETIME     DEFAULT NULL            COMMENT '最近一次发布时间',
  `published_by`   VARCHAR(64)  DEFAULT NULL            COMMENT '最近一次发布人',
  `created_by`     VARCHAR(64)  DEFAULT NULL,
  `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `updated_by`     VARCHAR(64)  DEFAULT NULL,
  `updated_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`      BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_kb_doc_status` (`status`, `version`, `tenant_id`),
  KEY `idx_kb_doc_source` (`source_type`, `source_ref`(191), `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库文档主表（MD 正文 + 发布态 + 可见范围）';

-- ========== 2. 知识库切片表 ==========
-- heading_path / anchor / source_ref 共同构成 AI 回答的出处（sourceRef）。
-- embedding_ref / vector_status 为「后期切向量 / Coze」预留位，P0 不用、不迁移表。
-- status：1=有效、0=失效（版本发布 / 文档归档时置 0，检索器只查 1）。
CREATE TABLE IF NOT EXISTS `sys_kb_chunk` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `doc_id`         BIGINT       NOT NULL                COMMENT 'sys_kb_doc.id',
  `version`        INT          NOT NULL DEFAULT 1      COMMENT '所属文档版本',
  `seq`            INT          NOT NULL DEFAULT 0      COMMENT '文内顺序',
  `heading_path`   VARCHAR(512) DEFAULT NULL            COMMENT '标题路径，如 H1 > H2 > H3（heading-aware 切片）',
  `anchor`         VARCHAR(191) DEFAULT NULL            COMMENT 'heading anchor，出处跳转用',
  `content`        LONGTEXT     NOT NULL                COMMENT '切片正文（MD 文本，表格整块保留）',
  `token_cnt`      INT          NOT NULL DEFAULT 0      COMMENT '估算 token 数',
  `source_ref`     VARCHAR(512) DEFAULT NULL            COMMENT '出处串（docId/version/heading/anchor）',
  `embedding_ref`  VARCHAR(512) DEFAULT NULL            COMMENT '向量库引用（预留，P0 为空）',
  `vector_status`  VARCHAR(32)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE|PENDING|READY|FAILED（预留，P0 恒 NONE）',
  `status`         TINYINT      NOT NULL DEFAULT 1      COMMENT '1 有效 / 0 失效',
  `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `tenant_id`      BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_kb_chunk_doc` (`doc_id`, `version`, `status`, `tenant_id`),
  FULLTEXT KEY `ft_kb_chunk` (`heading_path`, `content`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库切片（ngram 全文召回 + 重排，带出处）';

-- ========== 3. 知识库附件表 ==========
-- role 取值来自字典 kb_attachment_role（V206）：INLINE/REFERENCE/ORIGINAL/EXPIRED。
-- EXPIRED = 失效版本原件：不检索、不出域、仅留痕可下载（发布 v+1 时批量转换）。
CREATE TABLE IF NOT EXISTS `sys_kb_attachment` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT,
  `doc_id`         BIGINT      NOT NULL                COMMENT 'sys_kb_doc.id',
  `file_id`        BIGINT      NOT NULL                COMMENT 'sys_file_storage.id（bizType=kb_doc）',
  `role`           VARCHAR(32) NOT NULL DEFAULT 'REFERENCE' COMMENT '字典 kb_attachment_role',
  `indexable`      TINYINT     NOT NULL DEFAULT 0      COMMENT '是否单独切片检索（REFERENCE=1，其余 0）',
  `egress_allowed` TINYINT     NOT NULL DEFAULT 0      COMMENT '是否允许出域（ORIGINAL/EXPIRED 恒 0）',
  `expired_at`     DATETIME    DEFAULT NULL            COMMENT '转 EXPIRED 的时间',
  `sort_no`        INT         NOT NULL DEFAULT 0,
  `created_by`     VARCHAR(64) DEFAULT NULL,
  `created_at`     DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `tenant_id`      BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_kb_att_doc` (`doc_id`, `role`, `tenant_id`),
  KEY `idx_kb_att_file` (`file_id`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库附件（正文引用 / 补充材料 / 原始件 / 失效件）';

-- ========== 4. 操作权限点（接口放行用） ==========
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('ai:kb:view',    '知识库查看', 'API', 'READ',    'ENABLED', 0),
('ai:kb:edit',    '知识库编辑', 'API', 'WRITE',   'ENABLED', 0),
('ai:kb:publish', '知识库发布', 'API', 'EXECUTE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`),
                        `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- ========== 5. 菜单（AI中心 95 下，sort_no=3，与 94 / 9502 并列） ==========
-- route_path 沿用同级 AI 菜单的 /system/* 口径（不是 /ai/*）：侧栏 index 用绝对路径，
-- component 是 import.meta.glob 的视图相对路径，不带前导 '/'。
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(9503, '知识库', 95, '/system/knowledge', 'system/knowledge', 'ai:kb:view', 'MENU', 3, 1, 'ENABLED', 'Collection', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`),
                        `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`),
                        `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`), `icon` = VALUES(`icon`);

-- ========== 6. BUTTON 子节点（SeedIntegrityCheck 规则 2 + 前端 v-has-perm 显隐） ==========
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(95031, '知识库-新增', 9503, '', '', 'ai:kb:edit',    'BUTTON', 1, 0, 'ENABLED', '', 0),
(95032, '知识库-编辑', 9503, '', '', 'ai:kb:edit',    'BUTTON', 2, 0, 'ENABLED', '', 0),
(95033, '知识库-删除', 9503, '', '', 'ai:kb:edit',    'BUTTON', 3, 0, 'ENABLED', '', 0),
(95034, '知识库-发布', 9503, '', '', 'ai:kb:publish', 'BUTTON', 4, 0, 'ENABLED', '', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `perm_code` = VALUES(`perm_code`),
                        `menu_type` = VALUES(`menu_type`), `status` = VALUES(`status`);

-- ========== 7. 角色授权（仅 ROLE_ADMIN，与 V106 / V107 一致） ==========
-- 7.1 操作权限：后端 @RequirePerm 放行
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code IN ('ai:kb:view', 'ai:kb:edit', 'ai:kb:publish')
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

-- 7.2 菜单权限：前端侧栏显隐 + 按钮权限集
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id IN (9503, 95031, 95032, 95033, 95034)
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
