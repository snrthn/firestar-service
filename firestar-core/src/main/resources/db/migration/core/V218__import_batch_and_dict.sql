-- ============================================================================
-- V218：历史数据导入 · 异步批次表 + 字典 + 通知模板（方案 v3，迭代 1）
-- ----------------------------------------------------------------------------
-- 背景：P0(5)+P1(4) 共 9 类历史数据导入能力。审计按虎哥定版收敛为
--       「一次导入一条 sys_import_batch 摘要」，不建行级表、不写 sys_audit_log。
--
-- 关键设计（不可改）：
--   1) 同 key 串行靠生成列 active_key = IF(active_flag=1, import_key, NULL) + 唯一索引
--      uk_active(active_key)。**不能用 uk_active(import_key, active_flag)**：同 key 第二批
--      完成后 (key,0) 会与第一代终态 (key,0) 撞键；MySQL 唯一索引里 NULL 不参与去重，
--      生成列只在 active_flag=1 时占位，终态自动释放。
--   2) ext JSON 存失败行错误数组 + reconcile 差异摘要 + 金额合计（仅任务输出，不计审计）。
--   3) 不做逻辑删（无 is_deleted），永久留痕；只提供管理员归档（status=ARCHIVED）。
--   4) 原始 xlsx 存 sys_file_storage(biz_type='import')，本表只存 file_storage_id 指针。
--
-- db-check 基线影响：
--   tableCount 52→53（+sys_import_batch）；dictType 37→40（+3）；
--   dictData 162→179（+17：import_batch_status 9 + import_phase 6 + import_source 2）。
--   同步 DatabaseCheckService 三个常量，否则 health 直接 DOWN / db-check 503。
--   config / role 不变。
-- db-check-baseline: table=53, dictType=40, dictData=179, config=14, role=9, roleConflict=4
-- 幂等：全部 ON DUPLICATE KEY UPDATE / CREATE TABLE IF NOT EXISTS。
-- 归属：平台侧（导入框架通用能力），落 firestar-core。
-- ============================================================================

-- ---------- 1. 导入批次表 ----------
CREATE TABLE IF NOT EXISTS `sys_import_batch` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
  `tenant_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '租户 ID',
  `version`         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `batch_no`        VARCHAR(64)  NOT NULL COMMENT '批次号 IMP-{yyyyMMdd}-{uuid8}',
  `import_key`      VARCHAR(64)  NOT NULL COMMENT 'Handler key（customer/goods/...）',
  `operator_id`     BIGINT       NULL COMMENT '提交人 ID（审计摘要核心）',
  `file_name`       VARCHAR(255) NULL COMMENT '原始文件名',
  `file_sha256`     CHAR(64)     NULL COMMENT '原始文件 SHA-256（物证校验）',
  `file_storage_id` BIGINT       NULL COMMENT 'sys_file_storage.id 指针（biz_type=import）',
  `phase`           VARCHAR(32)  NOT NULL DEFAULT 'QUEUED' COMMENT '阶段: QUEUED/PARSING/VALIDATING/WRITING/RECONCILING/DONE',
  `status`          VARCHAR(32)  NOT NULL DEFAULT 'QUEUED' COMMENT '状态: QUEUED/PROCESSING/SUCCESS/PARTIAL/FAILED/ARCHIVED',
  `active_flag`     TINYINT      NOT NULL DEFAULT 0 COMMENT '1=处理中(占位), 0=终态(释放)',
  `active_key`      VARCHAR(64)  GENERATED ALWAYS AS (IF(`active_flag` = 1, `import_key`, NULL)) STORED COMMENT '生成列: 仅处理中占位, 供 uk_active 唯一约束',
  `total_rows`      INT          NOT NULL DEFAULT 0 COMMENT '解析出的总行数(上限 2000)',
  `success_rows`    INT          NOT NULL DEFAULT 0 COMMENT '成功行数',
  `fail_rows`       INT          NOT NULL DEFAULT 0 COMMENT '失败行数',
  `processed_rows`  INT          NOT NULL DEFAULT 0 COMMENT '已处理行数(进度)',
  `progress_pct`    INT          NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100',
  `parent_batch_no` VARCHAR(64)  NULL COMMENT '修正批次指向的原批次号',
  `started_at`      DATETIME     NULL COMMENT '后台开始处理时间',
  `finished_at`     DATETIME     NULL COMMENT '终态完成时间',
  `notify_sent`     TINYINT      NOT NULL DEFAULT 0 COMMENT '完成通知是否已发(防重)',
  `ext`             JSON         NULL COMMENT '失败行[{rowNo,rawData,errorMsg}] + reconcileSummary + amountTotal',
  `created_by`      BIGINT       NULL COMMENT '创建人 ID',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间(导入日期审计依据)',
  `updated_by`      BIGINT       NULL COMMENT '更新人 ID',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark`          VARCHAR(500) NULL COMMENT '备注 / 失败原因摘要',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_no` (`batch_no`),
  UNIQUE KEY `uk_active` (`active_key`),
  KEY `idx_operator_time` (`operator_id`, `created_at`),
  KEY `idx_import_key` (`import_key`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='历史数据导入批次(审计摘要, 一条=一次导入)';

-- ---------- 2. 字典：import_batch_status（9 项，含归档 ARCHIVED） ----------
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('import_batch_status', '导入批次状态', 'ENABLED', '导入批次状态机: QUEUED/PARSING/VALIDATING/WRITING/RECONCILING/SUCCESS/PARTIAL/FAILED/ARCHIVED', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`), `remark` = VALUES(`remark`);

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `style`, `status`, `tenant_id`) VALUES
('import_batch_status', '排队中', 'QUEUED', 1, 1, 'info', 'ENABLED', 0),
('import_batch_status', '解析中', 'PARSING', 2, 0, 'info', 'ENABLED', 0),
('import_batch_status', '校验中', 'VALIDATING', 3, 0, 'warning', 'ENABLED', 0),
('import_batch_status', '写入中', 'WRITING', 4, 0, 'warning', 'ENABLED', 0),
('import_batch_status', '对账中', 'RECONCILING', 5, 0, 'warning', 'ENABLED', 0),
('import_batch_status', '成功', 'SUCCESS', 6, 0, 'success', 'ENABLED', 0),
('import_batch_status', '部分成功', 'PARTIAL', 7, 0, 'warning', 'ENABLED', 0),
('import_batch_status', '失败', 'FAILED', 8, 0, 'danger', 'ENABLED', 0),
('import_batch_status', '已归档', 'ARCHIVED', 9, 0, 'info', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `style` = VALUES(`style`), `status` = VALUES(`status`);

-- ---------- 3. 字典：import_phase（6 项） ----------
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('import_phase', '导入阶段', 'ENABLED', '导入后台流水线阶段', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`), `remark` = VALUES(`remark`);

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `style`, `status`, `tenant_id`) VALUES
('import_phase', '排队', 'QUEUED', 1, 1, 'info', 'ENABLED', 0),
('import_phase', '解析', 'PARSING', 2, 0, 'info', 'ENABLED', 0),
('import_phase', '校验', 'VALIDATING', 3, 0, 'warning', 'ENABLED', 0),
('import_phase', '写入', 'WRITING', 4, 0, 'warning', 'ENABLED', 0),
('import_phase', '对账', 'RECONCILING', 5, 0, 'warning', 'ENABLED', 0),
('import_phase', '完成', 'DONE', 6, 0, 'success', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `style` = VALUES(`style`), `status` = VALUES(`status`);

-- ---------- 4. 字典：import_source（2 项） ----------
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('import_source', '导入来源', 'ENABLED', '单据来源标记: OPENING_IMPORT 期初导入 / IMPORT 普通导入', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`), `remark` = VALUES(`remark`);

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `style`, `status`, `tenant_id`) VALUES
('import_source', '期初导入', 'OPENING_IMPORT', 1, 1, 'warning', 'ENABLED', 0),
('import_source', '普通导入', 'IMPORT', 2, 0, 'info', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `style` = VALUES(`style`), `status` = VALUES(`status`);

-- ---------- 5. 通知模板：import_result ----------
-- 变量名与 NotifyCommand 渲染模型对齐: {{batchNo}} {{importName}} {{result}} {{failRows}}
INSERT INTO `sys_notification_template`
  (`code`, `type`, `title_tpl`, `content_tpl`, `enabled`, `tenant_id`, `created_at`, `updated_at`)
VALUES
  ('IMPORT_RESULT', 'SYSTEM_NOTICE', '数据导入完成：{{importName}}',
   '批次 {{batchNo}} 已处理完成，结果：{{result}}，失败 {{failRows}} 行。请到导入历史查看。', 1, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE `type` = VALUES(`type`), `title_tpl` = VALUES(`title_tpl`),
  `content_tpl` = VALUES(`content_tpl`), `enabled` = VALUES(`enabled`);
