-- V41: 项目立项流程定义种子（Step 3 流程编排入口的配套配置）
--
-- 背景：ProjectServiceImpl.submit 按 process_code='project_initiate' 找 ENABLED 的流程定义
-- 发起实例。本种子预置一条可直接部署的立项审批链，让端到端链路开箱即用。
--
-- 节点设计（3 节点，覆盖三种审批人类型）：
--   node_1 ROLE  = ROLE_SUPERVISOR 主管审批（首节点不可驳回）
--   node_2 LEVEL = L3               经理级会签（多人同职级全推候选，会签语义见 MEMORY.md）
--   node_3 USER  = 1 (admin)        终审（可作驳回目标）
--
-- 幂等：uk_tenant_process_code(tenant_id, process_code) 唯一索引，重复执行只更新配置不改 key。
-- 注意：本行只存「配置」；部署（Flowable deployment）是运行时动作，需调 deploy 端点触发。

INSERT INTO sys_process_def
    (process_code, process_name, flowable_deployment_id, def_version, status, node_list, remark, tenant_id)
VALUES
    ('project_initiate', '项目立项审批', NULL, 1, 'ENABLED',
     '[
       {"seq":1,"nodeId":"node_1","name":"主管审批","type":"ROLE","assignee":"ROLE_SUPERVISOR","rejectable":false},
       {"seq":2,"nodeId":"node_2","name":"经理级会签","type":"LEVEL","assignee":"L3","rejectable":true},
       {"seq":3,"nodeId":"node_3","name":"终审","type":"USER","assignee":"1","rejectable":true}
     ]',
     'V41 种子：Step3 立项审批链。node_2 为会签节点（LevelResolver 解析 L3 全员）；部署后 start 即用。',
     0)
ON DUPLICATE KEY UPDATE
    process_name = VALUES(process_name),
    status      = VALUES(status),
    node_list   = VALUES(node_list),
    remark      = VALUES(remark);
