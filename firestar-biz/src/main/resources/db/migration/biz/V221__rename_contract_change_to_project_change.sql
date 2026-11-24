-- V221：变更单展示文案「合同变更」→「项目变更」（2026-11-24 虎哥拍板）
-- 背景：变更对象是立项单（fin_project，含合同金额字段），并非独立合同实体；
--       V220 落地时流程 code=project_change 已是「项目变更」语义，但面向人的展示名
--       （流程名/权限显示名/菜单名/通知模板）沿用了「合同变更」，产生语义歧义。
-- 范围：只改展示文案，不改任何 code / key / 表名 / 流程定义内容：
--       process_code=project_change、perm_code=project:change、fin_project_change(_item) 全部保持。
-- 幂等：全部带 WHERE 精确匹配旧值，已改过则 0 行受影响，可安全重放。
-- 基线：不建表、不动字典/配置/角色，table=55 等基线标记沿用 V220，无三处同步需求。
-- db-check-baseline: table=55, dictType=40, dictData=179, config=14, role=9, roleConflict=4

-- 1. 流程展示名 + 备注（流程定义本体 node_list 不动）
UPDATE `sys_process_def`
   SET `process_name` = '项目变更审批',
       `remark` = REPLACE(`remark`, '合同变更', '项目变更')
 WHERE `process_code` = 'project_change'
   AND `process_name` = '合同变更审批';

-- 2. 权限点显示名（perm_code=project:change 不变，前端 v-has-perm 不受影响）
UPDATE `sys_permission`
   SET `perm_name` = '发起项目变更'
 WHERE `perm_code` = 'project:change'
   AND `perm_name` = '发起合同变更';

-- 3. 菜单按钮名（id=330 固定，菜单挂载不变）
UPDATE `sys_menu`
   SET `menu_name` = '发起项目变更'
 WHERE `id` = 330 AND `tenant_id` = 0
   AND `menu_name` = '发起合同变更';

-- 4. 通知模板：template_name / title_tpl / content_tpl 三列，仅改 CHANGE_* 四个模板里的「合同变更」字样
UPDATE `sys_notification_template`
   SET `template_name` = REPLACE(`template_name`, '合同变更', '项目变更'),
       `title_tpl`     = REPLACE(`title_tpl`, '合同变更', '项目变更'),
       `content_tpl`   = REPLACE(`content_tpl`, '合同变更', '项目变更')
 WHERE `code` IN ('CHANGE_SUBMITTED', 'CHANGE_APPROVED', 'CHANGE_REJECTED', 'CHANGE_WITHDRAWN');
