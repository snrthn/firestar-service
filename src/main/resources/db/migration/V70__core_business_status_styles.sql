-- ============================================================================
-- V70：核心业务流程状态字典补 style（标签取色，对齐 useDictLabels 约定）。
--
-- 背景：sys_dict_data.style 列（V1 基线，字典 dict_style：
-- primary/success/warning/danger/info）是给前端 el-tag 用的取色字段。
-- 复盘核心业务流转（立项→审批→发货→应收→收款→开票→核销→退货）后发现，
-- 下列 4 个状态字典在 V2 种子阶段漏带 style 列（之后也无人补），导致这些
-- 状态在前端恒为 NULL → el-tag 兜底 'info'（全灰），无法一眼区分「待处理 /
-- 进行中 / 已达成 / 异常」，起不到提醒作用：
--   - receivable_status  应收状态       （V2 种子无 style）
--   - invoice_status     开票状态       （V2 种子无 style；V63 仅给新增的
--                                        CANCELLED=info / REJECTED=danger 配色，
--                                        老三条 PENDING/ISSUING/ISSUED 仍 NULL，本迁移补齐）
--   - writeoff_status    核销状态       （V2 种子无 style）
--   - return_execute_status 退货执行状态（V2 种子无 style）
-- 已带 style 的财务/合同/发货状态字典（receipt_status / return_approval_status /
-- project_contract_status / shipment_status / shipment_line_status /
-- project_approval_status）维持原配色，不覆盖。
--
-- 配色语义（与 V44/V45/V51 既有约定一致，warning=橙·需关注/提醒、primary=蓝·
-- 进行中、success=绿·终态达成、danger=红·异常）：
--   待确认 / 部分核销 / 待开票 / 已反核销 / 未执行  → warning（提醒：有待办）
--   已确认 / 开票中 / 执行中                       → primary（进行中）
--   全部核销 / 已开票 / 正常 / 已完成               → success（终态/健康）
--
-- 幂等：纯 UPDATE，可重复执行；不动任何计数（DatabaseCheckService 不跟踪 style，
--       且本迁移不增删行，dictDataCount / dictTypeCount 保持不变）。
-- ============================================================================

-- 应收状态 receivable_status
UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'receivable_status' AND `dict_value` = 'PENDING_CONFIRM';
UPDATE `sys_dict_data` SET `style` = 'primary' WHERE `type_code` = 'receivable_status' AND `dict_value` = 'CONFIRMED';
UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'receivable_status' AND `dict_value` = 'PARTIAL_WRITEOFF';
UPDATE `sys_dict_data` SET `style` = 'success' WHERE `type_code` = 'receivable_status' AND `dict_value` = 'FULL_WRITEOFF';

-- 开票状态 invoice_status（CANCELLED / REJECTED 已由 V63 配色，此处不重复）
UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'invoice_status' AND `dict_value` = 'PENDING';
UPDATE `sys_dict_data` SET `style` = 'primary' WHERE `type_code` = 'invoice_status' AND `dict_value` = 'ISSUING';
UPDATE `sys_dict_data` SET `style` = 'success' WHERE `type_code` = 'invoice_status' AND `dict_value` = 'ISSUED';

-- 核销状态 writeoff_status
UPDATE `sys_dict_data` SET `style` = 'success' WHERE `type_code` = 'writeoff_status' AND `dict_value` = 'NORMAL';
UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'writeoff_status' AND `dict_value` = 'REVERSED';

-- 退货执行状态 return_execute_status
UPDATE `sys_dict_data` SET `style` = 'warning' WHERE `type_code` = 'return_execute_status' AND `dict_value` = 'NOT_EXECUTED';
UPDATE `sys_dict_data` SET `style` = 'primary' WHERE `type_code` = 'return_execute_status' AND `dict_value` = 'EXECUTING';
UPDATE `sys_dict_data` SET `style` = 'success' WHERE `type_code` = 'return_execute_status' AND `dict_value` = 'COMPLETED';
