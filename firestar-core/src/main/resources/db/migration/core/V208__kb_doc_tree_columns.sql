-- ============================================================================
-- V208：知识库文档树形结构（方案 A，2026-11-09）
--
-- 背景（设计见 docs/知识库模块设计.md §13）：
--   V207 建的 sys_kb_doc 是平铺表，没有任何层级字段；虎哥确认界面要对齐
--   飞书文档知识库的树形结构。方案 A = 节点同构（飞书 wiki node 模型）：
--   文档与文件夹共用 sys_kb_doc 一行，文件夹 content_md 为 NULL。
--
-- 为什么不加表、不动 db-check 计数：
--   ① 表数仍 51（EXPECTED_TABLE_COUNT），dict_type 36 / dict_data 154 不变；
--      node_type 与 V207 的 status / source_type 一样是 Java enum 硬编码，
--      **不占字典行**（V206 已确立的口径，别手贱往字典里加）。
--   ② 树形操作（新建文件夹 / 移动 / 删除节点）复用现有 ai:kb:edit，
--      不新增菜单与权限点；9503 / 95031~95034 保持不变。
--
-- ⚠️ MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，也不生效 CHECK 约束。
--    Flyway 按版本号保证本脚本只执行一次；防环、文件夹无正文、删除级联
--    等约束全部由应用层保证（Service 复用 MenuServiceImpl.validateNoCycle）。
--
-- 依赖：V207（sys_kb_doc 已建）；幂等：无（Flyway 单次执行）。
-- ============================================================================

ALTER TABLE `sys_kb_doc`
  ADD COLUMN `parent_id`  BIGINT       NOT NULL DEFAULT 0     COMMENT '父节点 ID，0 = 根节点',
  ADD COLUMN `node_type`  VARCHAR(16)  NOT NULL DEFAULT 'DOC' COMMENT 'DOC 文档 | FOLDER 文件夹（Java enum，不占字典行）',
  ADD COLUMN `sort_no`    INT          NOT NULL DEFAULT 0     COMMENT '同级排序号，小在前',
  ADD COLUMN `path`       VARCHAR(512) DEFAULT NULL           COMMENT '物化路径 /1/3/7/，用于面包屑、子树查询、权限继承与防环',
  ADD KEY `idx_kb_doc_parent` (`parent_id`, `sort_no`, `tenant_id`),
  ADD KEY `idx_kb_doc_path`   (`path`(191), `tenant_id`);

-- 存量行回填（V207 建表后尚无写入路径，此处兜底；有数据也不会丢）：
--   平铺历史文档一律挂根节点，按 id 当同级序号，物化路径写成 /<id>/。
UPDATE `sys_kb_doc`
   SET `parent_id` = 0,
       `node_type` = 'DOC',
       `sort_no`   = `id`,
       `path`      = CONCAT('/', `id`, '/')
 WHERE `path` IS NULL;
