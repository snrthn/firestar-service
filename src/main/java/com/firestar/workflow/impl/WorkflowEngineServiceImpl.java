package com.firestar.workflow.impl;

import com.firestar.dto.DeployResult;
import com.firestar.dto.NodeCfg;
import com.firestar.dto.ProcessDefVersionVO;
import com.firestar.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.*;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * WorkflowEngineService 默认实现。
 *
 * <p>仅注入 {@link RepositoryService}（部署 / 查询定义所需）。RuntimeService / TaskService 留待 Step 3
 * 接审批时再注入，避免本阶段引入无关依赖。</p>
 *
 * <p>BPMN 生成严禁手拼 XML 字符串 —— 一律走 {@link BpmnModel} API（P3 方案纪律，可避免命名空间/转义类低级 bug）。</p>
 */
@Slf4j
@Service
public class WorkflowEngineServiceImpl implements WorkflowEngineService {

    private final RepositoryService repositoryService;

    @Autowired
    public WorkflowEngineServiceImpl(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @Override
    public BpmnModel generateBpmn(String processKey, String processName, List<NodeCfg> nodes) {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId(processKey);
        process.setName(processName);
        model.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        EndEvent end = new EndEvent();
        end.setId("end");
        process.addFlowElement(end);

        String prev = "start";
        for (NodeCfg n : nodes) {
            UserTask t = new UserTask();
            t.setId(n.getNodeId());
            t.setName(n.getName());
            String type = n.getType() == null ? "USER" : n.getType();
            switch (type) {
                case "ROLE":
                    // 候选组用 role_code（与 §0-C 决策一致：candidateGroups = sys_role.role_code）
                    t.setCandidateGroups(Collections.singletonList(String.valueOf(n.getAssignee())));
                    break;
                case "LEVEL":
                    // Step 3 注册 LevelResolver bean 后运行时解析实际审批人
                    t.setAssignee("${levelResolver.resolve(execution)}");
                    break;
                case "USER":
                default:
                    t.setAssignee(String.valueOf(n.getAssignee()));
                    break;
            }
            process.addFlowElement(t);
            process.addFlowElement(sf("f_" + prev + "_" + n.getNodeId(), prev, n.getNodeId()));
            prev = n.getNodeId();
        }
        process.addFlowElement(sf("f_" + prev + "_end", prev, "end"));
        return model;
    }

    @Override
    public DeployResult deploy(String processKey, String processName, BpmnModel model) {
        byte[] xml = new BpmnXMLConverter().convertToXML(model);
        Deployment d = repositoryService.createDeployment()
                .addBytes(processKey + ".bpmn20.xml", xml)
                .name(processName)
                .deploy();
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .deploymentId(d.getId())
                .singleResult();
        log.info("[workflow] 部署流程 key={} name={} version={} deploymentId={}",
                processKey, processName, pd.getVersion(), d.getId());
        return new DeployResult(d.getId(), pd.getId(), pd.getVersion());
    }

    @Override
    public List<ProcessDefVersionVO> listVersions(String processKey) {
        List<ProcessDefinition> pds = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey)
                .orderByProcessDefinitionVersion().desc()
                .list();
        List<ProcessDefVersionVO> vos = new ArrayList<>();
        for (ProcessDefinition pd : pds) {
            ProcessDefVersionVO vo = new ProcessDefVersionVO();
            vo.setVersion(pd.getVersion());
            vo.setProcessDefinitionId(pd.getId());
            vo.setDeploymentId(pd.getDeploymentId());
            vo.setName(pd.getName());
            vo.setSuspended(pd.isSuspended());
            vos.add(vo);
        }
        return vos;
    }

    private SequenceFlow sf(String id, String src, String tgt) {
        SequenceFlow f = new SequenceFlow(src, tgt);
        f.setId(id);
        return f;
    }

    // ===================== Step 3 占位 =====================

    @Override
    public String start(String processKey, Map<String, Object> variables) {
        throw new UnsupportedOperationException("Step 3 未实现：start");
    }

    @Override
    public void complete(String taskId, Map<String, Object> variables) {
        throw new UnsupportedOperationException("Step 3 未实现：complete");
    }

    @Override
    public void moveTo(String processInstanceId, String fromNode, String toNode) {
        throw new UnsupportedOperationException("Step 3 未实现：moveTo");
    }

    @Override
    public List<?> listTasks(String processInstanceId) {
        throw new UnsupportedOperationException("Step 3 未实现：listTasks");
    }
}
