-- ============================================================================
-- V200：回填 INVOICE_PENDING_ISSUE 通知模板的 template_name
-- ----------------------------------------------------------------------------
-- 背景（虎哥 2026-11-04 反馈「通知类型里又有一个 INVOICE_PENDING_ISSUE 没在字典中」）：
--   管理端【通知记录】页「通知类型」下拉 / 「类型名称」列的数据源是
--   sys_notification_template（GET /admin/notifications/templates），中文名取
--   template_name（前端 types/notification.ts 的 templateLabel：为空则兜底显示 code）。
--   它不经过 sys_dict_data，系统里也不存在 notification_type 之类的字典类型——
--   所以「没在字典中」实际是：该类型以裸码 INVOICE_PENDING_ISSUE 示人，没有中文名。
--
-- 根因：V97__invoice_issue_closed_loop.sql 第 36 行 INSERT 的列清单漏了
--   template_name（V31 加列时 NOT NULL DEFAULT ''，不指定即落空串），于是前端兜底显示裸码。
--   这是 V55 血泪教训的第三次重演：V50 漏列 → V55 回填；V56 已专门写过
--   「template_name 必须给」，V97 仍踩坑。V97 已执行，按 Flyway checksum 铁律不可改，
--   只能新增迁移回填（照 V55 范式）。
--
-- 幂等：只回填名字为空的行，运营在管理端改过名的模板不被重置；本迁移被手动先行执行、
--   Flyway 重跑时命中 0 行亦无副作用。uk_tpl_code 保证最多命中 1 行。
-- 影响面：仅 sys_notification_template.template_name，不动 sys_dict_type / sys_dict_data，
--   故 DatabaseCheckService.readSeedData 的 35 / 146 基线不变，无计数同步项。
-- 归属：业务侧（开票通知），落 firestar-biz；版本号遵 README「新增业务迁移用 V200+」。
-- ============================================================================

UPDATE `sys_notification_template` SET `template_name` = '待开票通知'
  WHERE `code` = 'INVOICE_PENDING_ISSUE' AND (`template_name` IS NULL OR `template_name` = '');
