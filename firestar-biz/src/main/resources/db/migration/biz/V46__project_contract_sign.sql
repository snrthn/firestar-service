-- ============================================================================
-- V46：立项签约后置（contract_desc / contract_signed_at / contract_signed_by）
-- 依赖：V1（fin_project 已建）、V2（project_contract_status 字典种子）
-- 背景：虎哥 2026-10-22 拍板——签约从「建项表单字段」后移为「审批通过后的独立动作」：
--       表单不再选合同状态（create 强制 UNSIGNED），立项审批通过后操作栏出「签约」按钮，
--       弹窗可修正最终合同金额 + 补录签约描述（非必填），本迁移补三列承接。
-- 决策：a 不可撤销签约；b 金额签约时可改；c 签约时间/签约人记录并展示。
-- 幂等：Flyway 版本号保证单次执行；UPDATE 归一化语句可重复执行。
-- ============================================================================

-- ========== 1. fin_project 加签约三列（V39 给 sys_user 加 level_code 同款范式） ==========
ALTER TABLE `fin_project`
  ADD COLUMN `contract_desc` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '签约描述/签约留言（签约时补录，非必填）',
  ADD COLUMN `contract_signed_at` datetime DEFAULT NULL COMMENT '签约时间',
  ADD COLUMN `contract_signed_by` bigint DEFAULT NULL COMMENT '签约人 → sys_user.id';

-- ========== 2. 老数据归一化（可重复执行） ==========
-- contract_status 历史上是建项表单手动选，MP_TEST 老数据里残留 PENDING_APPROVAL 等
-- 旧状态机遗物（当前状态机只有 UNSIGNED / SIGNED）。统一洗为 UNSIGNED（未签约）。
UPDATE `fin_project`
SET `contract_status` = 'UNSIGNED'
WHERE `contract_status` IS NULL OR `contract_status` NOT IN ('UNSIGNED', 'SIGNED');
