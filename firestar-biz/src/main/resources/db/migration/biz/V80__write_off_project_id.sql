-- V80 2026-10-28：fin_write_off 增加 project_id 列，支撑核销口径（F-08 本期核销额 / F-09 差异池）
-- 随「项目 / 客户 / 经理」筛选下推。此前 fin_write_off 无 project_id，核销口径指标只能按全公司汇总，
-- 与「项目 + 客户」联动筛选的预期不符。
--
-- 迁移纪律：本脚本为全新 V80，不回改任何已执行迁移（V1..V79 校验和锁死，回头改会让应用起不来）。
-- 历史数据回填用裸 SQL（不受 MyBatis-Plus @TableLogic 拦截，软删行的 project_id 仍有效）。

ALTER TABLE fin_write_off
  ADD COLUMN project_id BIGINT NULL COMMENT '关联项目ID（核销口径下推用，V80 新增）';

ALTER TABLE fin_write_off
  ADD INDEX idx_write_off_project (project_id);

-- 历史数据回填：优先取应收单项目，其次收款单项目
UPDATE fin_write_off wo
  LEFT JOIN fin_receivable r ON wo.receivable_id = r.id
  LEFT JOIN fin_receipt rc ON wo.receipt_id = rc.id
  SET wo.project_id = COALESCE(r.project_id, rc.project_id)
  WHERE wo.project_id IS NULL;
