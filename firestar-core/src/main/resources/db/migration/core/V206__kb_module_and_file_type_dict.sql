-- ============================================================================
-- V206：file_type 值域收窄（纯媒体格式）+ 知识库字典预备（2026-11-08）
--
-- 背景（设计见 docs/知识库模块设计.md §2 / §4）：
--   file_type 原 6 项混了 3 个维度：BANK_RECEIPT/INVOICE（业务凭证，与
--   file_biz_type.receipt/invoice 重复且大小写不一）、TEMP（生命周期）、
--   AUDIO/VIDEO（媒体格式）、ATTACHMENT（兜底）。V24 为 mp3/mp4 补 AUDIO/VIDEO
--   时把格式塞进该字典，是语义漂移的原点。本迁移把值域收窄为 5 项纯媒体类型：
--     IMAGE / PDF / AUDIO / VIDEO / ATTACHMENT
--   字段名从此名副其实；业务归属统一由 file_biz_type 承担。
--
--   同时落下知识库模块的两组字典（表 / 菜单 / 权限在后续切面，本迁移不建表）：
--     file_biz_type + kb_doc（知识库附件回查与 OSS 首层目录）
--     新增字典类型 kb_attachment_role + 4 项（INLINE/REFERENCE/ORIGINAL/EXPIRED）
--
-- ⚠️ 本迁移【不建表、不建菜单】：sys_kb_doc / sys_kb_chunk / sys_kb_attachment、
--    菜单 9503 与 ai:kb:* 权限点留到下一个切面（V207），届时表数 48→51。
--    因此 DatabaseCheckService 本次只改字典计数：
--      EXPECTED_DICT_TYPE_COUNT 35→36（新增 kb_attachment_role 一个类型）
--      EXPECTED_DICT_DATA_COUNT 150→154（见下方逐笔对账）
--      EXPECTED_TABLE_COUNT 保持 48（不变！）
--    漏改会 /actuator/health DOWN → 503（V19/V20/V22 三次同款事故）。
--
-- 字典行数对账（sys_dict_data，基线 150 已由 V201 实测对账）：
--   ① DELETE file_type 旧 3 项                         150 → 147
--   ② UPSERT file_type 5 项：IMAGE/PDF 新增 2 项，
--      AUDIO/VIDEO/ATTACHMENT 命中唯一键仅更新不新增   147 → 149
--   ③ file_biz_type + kb_doc                          149 → 150
--   ④ kb_attachment_role 4 项                        150 → 154
--   dict_type：35 → 36（新增 1 个类型，不新增行数）
--
-- 幂等性：DELETE 二次执行命中 0 行；INSERT 均带 ON DUPLICATE KEY UPDATE
--   （sys_dict_data 唯一键 tenant_id+type_code+dict_value、
--     sys_dict_type 唯一键 tenant_id+type_code）；UPDATE 存量行带 WHERE 收窄条件。
--
-- 存量数据：sys_file_storage.file_type 里 BANK_RECEIPT/INVOICE/TEMP 的老行
--   按 storage_path 扩展名重刷，映射不到的一律 ATTACHMENT。上线前建议先跑：
--   SELECT file_type, COUNT(*) FROM sys_file_storage GROUP BY file_type;
-- ============================================================================

-- ========== 1. file_type 值域收窄 ==========
DELETE FROM `sys_dict_data`
 WHERE `type_code` = 'file_type'
   AND `dict_value` IN ('BANK_RECEIPT', 'INVOICE', 'TEMP');

INSERT INTO `sys_dict_data`
  (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
  ('file_type', '图片', 'IMAGE',      1, 0, 'ENABLED', 0),
  ('file_type', 'PDF',  'PDF',        2, 0, 'ENABLED', 0),
  ('file_type', '音频', 'AUDIO',      3, 0, 'ENABLED', 0),
  ('file_type', '视频', 'VIDEO',      4, 0, 'ENABLED', 0),
  ('file_type', '附件', 'ATTACHMENT', 5, 1, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE
  `dict_label` = VALUES(`dict_label`),
  `sort_no`    = VALUES(`sort_no`),
  `is_default` = VALUES(`is_default`),
  `status`     = VALUES(`status`);

-- ========== 2. 存量文件记录清洗（MySQL 5.7 兼容，不用正则/窗口函数） ==========
UPDATE `sys_file_storage`
SET `file_type` = CASE
  WHEN LOWER(SUBSTRING_INDEX(`storage_path`, '.', -1))
       IN ('jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp') THEN 'IMAGE'
  WHEN LOWER(SUBSTRING_INDEX(`storage_path`, '.', -1)) = 'pdf'  THEN 'PDF'
  WHEN LOWER(SUBSTRING_INDEX(`storage_path`, '.', -1)) = 'mp3'  THEN 'AUDIO'
  WHEN LOWER(SUBSTRING_INDEX(`storage_path`, '.', -1)) = 'mp4'  THEN 'VIDEO'
  ELSE 'ATTACHMENT' END
WHERE `file_type` IN ('BANK_RECEIPT', 'INVOICE', 'TEMP');

-- ========== 3. 知识库附件业务归属（当前最大 sort_no=9，故用 10） ==========
INSERT INTO `sys_dict_data`
  (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
  ('file_biz_type', '知识库文档', 'kb_doc', 10, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE
  `dict_label` = VALUES(`dict_label`),
  `sort_no`    = VALUES(`sort_no`),
  `status`     = VALUES(`status`);

-- ========== 4. 知识库附件角色（域内枚举，不污染 file_type） ==========
INSERT INTO `sys_dict_type`
  (`type_code`, `type_name`, `status`, `remark`, `tenant_id`)
VALUES
  ('kb_attachment_role', '知识库附件角色', 'ENABLED',
   'sys_kb_attachment.role：正文引用/补充材料/当前原始件/失效版本原件', 0)
ON DUPLICATE KEY UPDATE
  `type_name` = VALUES(`type_name`),
  `status`    = VALUES(`status`),
  `remark`    = VALUES(`remark`);

INSERT INTO `sys_dict_data`
  (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
  ('kb_attachment_role', '正文引用',     'INLINE',    1, 0, 'ENABLED', 0),
  ('kb_attachment_role', '补充材料',     'REFERENCE', 2, 0, 'ENABLED', 0),
  ('kb_attachment_role', '当前原始件',   'ORIGINAL',  3, 0, 'ENABLED', 0),
  ('kb_attachment_role', '失效版本原件', 'EXPIRED',   4, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE
  `dict_label` = VALUES(`dict_label`),
  `sort_no`    = VALUES(`sort_no`),
  `status`     = VALUES(`status`);
