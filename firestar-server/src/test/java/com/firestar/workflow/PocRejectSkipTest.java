package com.firestar.workflow;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.*;
import org.flowable.engine.*;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 审批工作流 POC（A2 拍板「走论证线路」）。
 * 目标：用 BpmnModel 动态生成 3 节点流程 A→B→C，验证「驳回到 X → X 及之后重走、X 之前已审节点跳过」语义。
 * 关键修正：文档附录 B 的 skipBeforeSeq（= 驳回目标 seq）有边界 bug —— 驳回到 A(seq=1) 时
 *   nodeSeq(1) > 1 为 false，连 A 自己都会被跳过。改用 skipNodeIds 集合（已审过且非驳回目标的节点）才正确。
 * 不依赖业务表、不写业务代码，纯 Flowable 引擎论证。
 */
@SpringBootTest
@ActiveProfiles("dev")
class PocRejectSkipTest {

    @Autowired ProcessEngine engine;
    @Autowired RepositoryService repositoryService;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;

    private static final String U = "demoUser";

    /** 动态生成 3 节点流程：start → A → gw1 →(B | 跳过B到C) → B → gw2 →(C | 跳过C到end) → C → end */
    private BpmnModel buildModel(String procId) {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId(procId);
        process.setName("POC_" + procId);
        model.addProcess(process);

        StartEvent start = new StartEvent(); start.setId("start"); process.addFlowElement(start);
        EndEvent end = new EndEvent(); end.setId("end"); process.addFlowElement(end);

        for (String id : new String[]{"node_1", "node_2", "node_3"}) {
            UserTask t = new UserTask();
            t.setId(id); t.setName(id); t.setAssignee(U);
            process.addFlowElement(t);
        }

        process.addFlowElement(sf("f_start_1", "start", "node_1"));

        ExclusiveGateway gw1 = new ExclusiveGateway(); gw1.setId("gw1"); process.addFlowElement(gw1);
        process.addFlowElement(sf("f_1_gw1", "node_1", "gw1"));
        SequenceFlow toB = sf("f_gw1_b", "gw1", "node_2");
        toB.setConditionExpression("${!skipNodeIds.contains('node_2')}");
        process.addFlowElement(toB);
        SequenceFlow skipB = sf("f_gw1_skip", "gw1", "node_3");
        process.addFlowElement(skipB);
        gw1.setDefaultFlow("f_gw1_skip");

        ExclusiveGateway gw2 = new ExclusiveGateway(); gw2.setId("gw2"); process.addFlowElement(gw2);
        process.addFlowElement(sf("f_2_gw2", "node_2", "gw2"));
        SequenceFlow toC = sf("f_gw2_c", "gw2", "node_3");
        toC.setConditionExpression("${!skipNodeIds.contains('node_3')}");
        process.addFlowElement(toC);
        SequenceFlow skipC = sf("f_gw2_end", "gw2", "end");
        process.addFlowElement(skipC);
        gw2.setDefaultFlow("f_gw2_end");

        process.addFlowElement(sf("f_3_end", "node_3", "end"));
        return model;
    }

    private SequenceFlow sf(String id, String src, String tgt) {
        SequenceFlow f = new SequenceFlow(src, tgt); f.setId(id); return f;
    }

    private String deploy(String procId) {
        String xml = new String(new BpmnXMLConverter().convertToXML(buildModel(procId)), StandardCharsets.UTF_8);
        Deployment d = repositoryService.createDeployment().addString(procId + ".bpmn20.xml", xml).deploy();
        return d.getId();
    }

    private String activeTaskId(ProcessInstance pi, String nodeId) {
        Task t = taskService.createTaskQuery().processInstanceId(pi.getId()).taskDefinitionKey(nodeId).singleResult();
        assertNotNull(t, "期望节点 " + nodeId + " 存在待办");
        return t.getId();
    }

    private void assertNoNode(ProcessInstance pi, String nodeId) {
        Task t = taskService.createTaskQuery().processInstanceId(pi.getId()).taskDefinitionKey(nodeId).singleResult();
        assertNull(t, "期望节点 " + nodeId + " 无待办（应被跳过）");
    }

    @Test
    void pocRejectSkip() {
        String procId = "poc_reject_" + System.nanoTime();
        deploy(procId);

        // POC1：动态生成 → 部署，首次 version=1
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(procId).latestVersion().singleResult();
        assertEquals(1, pd.getVersion(), "POC1: 首次部署 version 应为 1");

        // POC2：发起 → A 同意 → B 同意 → 停在 C
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(procId);
        runtimeService.setVariable(pi.getId(), "skipNodeIds", new ArrayList<String>());
        assertActiveNode("node_1");
        taskService.complete(activeTaskId(pi, "node_1"));
        assertActiveNode("node_2");
        taskService.complete(activeTaskId(pi, "node_2"));
        assertActiveNode("node_3"); // 停在 C

        // POC3：C 驳回至 A（记录 B 已审过、非驳回目标 → 重走时跳过）
        runtimeService.setVariable(pi.getId(), "skipNodeIds", List.of("node_2"));
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(pi.getId())
                .moveActivityIdTo("node_3", "node_1")
                .changeState();
        assertActiveNode("node_1");
        assertNoNode(pi, "node_2"); // B 不应有新任务

        // POC4：A 重新同意 → B 自动跳过 → 停在 C（最硬的一条）
        taskService.complete(activeTaskId(pi, "node_1"));
        assertNoNode(pi, "node_2");      // ✅ B 跳过
        assertActiveNode("node_3");      // ✅ 自动到 C
        taskService.complete(activeTaskId(pi, "node_3"));

        // 流程应结束
        ProcessInstance after = runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult();
        assertNull(after, "POC4: 全部走完后流程应结束");
    }

    @Test
    void pocRedeployVersion() {
        String procId = "poc_v_" + System.nanoTime();
        deploy(procId);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(procId);
        runtimeService.setVariable(pi.getId(), "skipNodeIds", new ArrayList<String>());

        // 第二次部署同 key → 应生成 VERSION_=2，且第一次的在途实例按 V1 走完
        deploy(procId);
        ProcessDefinition pd2 = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(procId).latestVersion().singleResult();
        assertEquals(2, pd2.getVersion(), "POC5: 重新部署 version 应为 2");

        ProcessInstance piNow = runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult();
        assertEquals(1, piNow.getProcessDefinitionVersion(), "POC5: 在途实例仍按 V1 走");

        // 清理：删除在途实例，避免遗留任务污染后续运行
        runtimeService.deleteProcessInstance(pi.getId(), "poc cleanup");
    }

    private void assertActiveNode(String nodeId) {
        // 便捷：当前 U 在途实例中该节点应有任务（通过单实例查询在 pocRejectSkip 内已持有 pi，这里复用通用查询）
        Task t = taskService.createTaskQuery().taskAssignee(U).taskDefinitionKey(nodeId).list().stream().findFirst().orElse(null);
        assertNotNull(t, "期望节点 " + nodeId + " 存在待办");
    }
}
