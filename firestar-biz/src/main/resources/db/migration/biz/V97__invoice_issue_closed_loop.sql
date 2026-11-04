-- ============================================================================
-- V97：发票开票闭环 —— 开票员可见全量 + 待开票通知模板
-- ----------------------------------------------------------------------------
-- 背景（虎哥 2026-11-01 拍板）：
--   V95 把开票拆成「申请人申请 → 开票员审批 → 开票员开票」三节点，但链路断在中间：
--     ① 审批通过只 notifyResult 通知了申请人(receivers=applicantId)，开票员侧零通知，
--        审批完就没人知道该去开票了；
--     ② 列表 DataScopeHelper.own(Invoice::getApplicantId, me) 让开票员只能看自己申请的单，
--        开票员被挡在门外，两头堵死；
--     ③ invoice:issue 只在 sys_menu 建了 BUTTON 节点（V96），从未 INSERT 进 sys_permission，
--        故 ROLE_INVOICER 按钮看得见、点下去 403（见下方「权限双链路」说明）。
--
-- 本脚本只做「通知模板」与「权限点」两处种子，其余都在代码层：
--   - 开票员可见全量     → InvoiceServiceImpl.page() 代码层放行
--   - 审批通过引导开票   → InvoiceServiceImpl.completeTask() 发 INVOICE_PENDING_ISSUE
--   - 开票人≠申请人硬校验 → InvoiceServiceImpl.issue() 代码层拦截
--
-- ⚠️ 权限双链路（严格分离，见 PermissionResolverImpl / AuthServiceImpl.loadPermissions）：
--    按钮【显示】→ sys_menu(menu_type='BUTTON') + sys_role_menu
--    接口【鉴权】→ sys_permission + sys_role_permission（PermissionInterceptor 比对 PERM:<code>）
--    PermissionResolverImpl 第 52 行注释已明示其代价：
--      「勾了菜单按钮但没勾操作权限 → 按钮显示、点击 403」。
--    invoice:issue 正踩中该坑 —— V96 只补了左侧链路，右侧从未建立。
--    ROLE_ADMIN 不受影响：PermissionInterceptor 对 admin 无条件放行（84-89 行），
--    所以这个洞历史上一直用 admin 测不出来。
--
-- ⚠️ 为何不复用 INVOICE_DONE：
--   其 content_tpl 为「…的发票已开具，发票号：{{invoiceNo}}」，语义是「已开票完成」。
--   此前 notifySubmitted(提交) / notifyResult(审批通过) 都复用它，导致审批通过时
--   给申请人推「发票已开具」——而此时票根本没开（invoice_no 为 null）。语义错误。
--   新增 INVOICE_PENDING_ISSUE 专用于「已审批通过、待开票员开票」这一节点。
-- ============================================================================

-- 1. 待开票通知模板（{{bizTitle}} {{applyNo}}）
--    ON DUPLICATE KEY UPDATE 保证重复执行幂等（Flyway  repair 后可安全重跑）
INSERT INTO `sys_notification_template`
    (`code`, `type`, `title_tpl`, `content_tpl`, `enabled`, `tenant_id`)
VALUES
    ('INVOICE_PENDING_ISSUE', 'INVOICE_PENDING_ISSUE',
     '待开票：{{bizTitle}}',
     '「{{bizTitle}}」的开票申请（业务单号 {{applyNo}}）已审批通过，等待开具。请前往开票管理填报真实发票号并上传电子票。',
     1, 0)
ON DUPLICATE KEY UPDATE
    `type`        = VALUES(`type`),
    `title_tpl`   = VALUES(`title_tpl`),
    `content_tpl` = VALUES(`content_tpl`),
    `enabled`     = VALUES(`enabled`);

-- 2. 操作权限点 invoice:issue（补 V95/V96 的漏项 —— 此前只存在于 sys_menu，从未进 sys_permission）
--    resource_type/action_type 用 BUTTON/EXECUTE，与同类的 invoice:create / invoice:edit
--    （V81 第 35-36 行）保持一致。PermissionResolver 不区分 resource_type，
--    只要 perm_code 在 sys_permission 且被 sys_role_permission 授权，接口即可放行。
--    ⚠️ sys_permission 有唯一键 uk_tenant_perm_code，裸 INSERT 会撞 Duplicate entry
--       导致整个 V97 迁移失败（V81 注释已警示），故必须 ON DUPLICATE KEY UPDATE。
INSERT INTO `sys_permission`
    (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`)
VALUES
    ('invoice:issue', '发票开票', 'BUTTON', 'EXECUTE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE
    `perm_name` = VALUES(`perm_name`),
    `status`    = 'ENABLED';

-- 3. 授权角色：ROLE_ADMIN + ROLE_INVOICER（范式照 V60 第 4/5 节）
--    SELECT ... FROM sys_role, sys_permission 的写法天然幂等：INSERT 的结果集行数 = 角色数 ×
--    权限点数，重复执行不会新增行（均命中唯一键走 UPDATE）；且第 2 节未建出该 permission 时
--    结果集为空 → 静默跳过，不会让迁移失败。
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code IN ('ROLE_ADMIN', 'ROLE_INVOICER')
  AND p.perm_code = 'invoice:issue'
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';
