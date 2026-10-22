-- V48: 审批历史数据回填——ACT_HI_TASKINST.ASSIGNEE_ / COMPLETED_BY_
--
-- 背景（2026-10-22 虎哥实测两个 bug 的共同根因）：
--   1) 审批中心「我审批的」Tab 查不到记录：引擎层 myApprovedHistory 原用
--      taskCompletedBy(username)，它走 ACT_HI_TASKINST.COMPLETED_BY_ 列——
--      Flowable 7.1.0 的 complete 链路实测不写该列（act_hi_comment.USER_ID_、
--      act_hi_procinst.START_USER_ID_ 都有值，唯独 COMPLETED_BY_ 全 NULL，DB 实锤），
--      查询恒空。引擎层已改为 taskAssignee(username) 查 ASSIGNEE_。
--   2) 旧数据 ASSIGNEE_ 也为 NULL：ROLE 候选组节点在 claim 修复（complete 前
--      claimAsCurrentUser，2026-10-22 上线）之前，主管/终审任务不 claim 直接
--      complete，历史表 ASSIGNEE_ 落空——「我审批的」对这些旧记录仍查不到。
--
-- 做法（纯 UPDATE，无 DDL，WHERE NULL 条件天然幂等，重跑安全）：
--   第一步：ASSIGNEE_ 为 NULL 的旧任务，用审批留言的 userId 回填
--           （ACT_HI_COMMENT.USER_ID_，addComment 时 authenticatedUserId 有值；
--            按 TASK_ID_ 关联，取该任务最后一条有 userId 的评论）；
--   第二步：COMPLETED_BY_ 一并无脑补齐为 ASSIGNEE_（双轨保险——将来若有人
--           再用 taskCompletedBy 也不会空）；
--   注意：本迁移只治 2026-10-22 claim 修复之前产生的旧数据；新数据由引擎层
--        claim 保证 ASSIGNEE_ 有值，不需要回填。

-- 第一步：评论 userId → ASSIGNEE_（仅补 NULL 行）
-- ⚠️ 2026-10-22 生产事故修复：表名必须大写（生产 MySQL lower_case_table_names=0
--   大小写敏感，小写报 ERROR 1146；本地 Windows 大小写不敏感测不出）。与 V3 对齐。
UPDATE ACT_HI_TASKINST t
    JOIN (
        SELECT c.TASK_ID_, MAX(c.USER_ID_) AS uid
        FROM ACT_HI_COMMENT c
        WHERE c.USER_ID_ IS NOT NULL AND c.USER_ID_ <> ''
        GROUP BY c.TASK_ID_
    ) x ON t.ID_ = x.TASK_ID_
SET t.ASSIGNEE_ = x.uid
WHERE t.ASSIGNEE_ IS NULL;

-- 第二步：COMPLETED_BY_ 补齐（ASSIGNEE_ 已有值的行；NULL 行无来源可填，跳过）
UPDATE ACT_HI_TASKINST
SET COMPLETED_BY_ = ASSIGNEE_
WHERE COMPLETED_BY_ IS NULL AND ASSIGNEE_ IS NOT NULL;
