package com.firestar.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.entity.ProcessDef;
import com.firestar.mapper.ProcessDefMapper;
import com.firestar.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.BpmnModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 流程定义启动自动部署器 —— 让配置化审批流程「开箱即部署」。
 *
 * <h3>解决什么</h3>
 * P3 引擎 {@code WorkflowEngineService#start} 要求 Flowable 里已存在该流程的 BPMN 部署，
 * 否则直接抛「流程未部署，无法发起」。但历史种子（V41 project_initiate）只在
 * {@code sys_process_def} 写了配置，注释明说「部署是运行时动作，需调 deploy 端点触发」——
 * 也就是说立项流程此前根本没部署进 Flowable，除非有人手动点过 deploy 端点。
 * 每套新流程都靠手动部署既脆弱又难验证 P3 通用性。本 Runner 在应用启动时把
 * {@code ENABLED} 且 {@code flowable_deployment_id IS NULL}（= 尚未部署）的流程定义
 * 自动部署进 Flowable，回写部署 ID 与版本，使流程「开箱即用」。
 *
 * <h3>契约（改之前先读）</h3>
 * <ul>
 *   <li>仅部署 {@code flowable_deployment_id IS NULL} 的流程；已部署的（含配置变更后管理员
 *       经 deploy 端点重新部署的）不重复部署，避免每次重启都涨 Flowable 版本号；</li>
 *   <li>逐条部署，单条失败只记 WARN 并继续，绝不中断其它流程的部署；</li>
 *   <li>任何异常都只记日志，<b>绝不影响启动</b>（ApplicationRunner 抛异常会让应用启动失败）；</li>
 *   <li>{@code @Order(Ordered.LOWEST_PRECEDENCE)}：排在 Flyway / Flowable 引擎初始化、
 *       StartupHealthReporter 等之后，确保依赖就绪再部署。</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProcessDefAutoDeployRunner implements ApplicationRunner {

    private final ObjectProvider<ProcessDefMapper> processDefMapperProvider;
    private final ObjectProvider<WorkflowEngineService> engineProvider;

    public ProcessDefAutoDeployRunner(ObjectProvider<ProcessDefMapper> processDefMapperProvider,
                                     ObjectProvider<WorkflowEngineService> engineProvider) {
        this.processDefMapperProvider = processDefMapperProvider;
        this.engineProvider = engineProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        ProcessDefMapper mapper = processDefMapperProvider.getIfAvailable();
        WorkflowEngineService engine = engineProvider.getIfAvailable();
        if (mapper == null || engine == null) {
            log.info("[process-auto-deploy] ProcessDefMapper / WorkflowEngineService 不可用，跳过自动部署");
            return;
        }
        try {
            List<ProcessDef> pending = mapper.selectList(new LambdaQueryWrapper<ProcessDef>()
                    .eq(ProcessDef::getStatus, "ENABLED")
                    .isNull(ProcessDef::getFlowableDeploymentId));
            if (pending.isEmpty()) {
                log.info("[process-auto-deploy] 无待部署流程定义（flowable_deployment_id 均非空），跳过");
                return;
            }
            int ok = 0;
            for (ProcessDef def : pending) {
                if (def.getNodeList() == null || def.getNodeList().isEmpty()) {
                    log.warn("[process-auto-deploy] 流程 {} 节点未配置，跳过部署", def.getProcessCode());
                    continue;
                }
                try {
                    BpmnModel model = engine.generateBpmn(
                            def.getProcessCode(), def.getProcessName(), def.getNodeList());
                    var result = engine.deploy(def.getProcessCode(), def.getProcessName(), model);
                    def.setFlowableDeploymentId(result.getDeploymentId());
                    def.setDefVersion(result.getVersion());
                    mapper.updateById(def);
                    ok++;
                    log.info("[process-auto-deploy] 已部署 code={} version={} deploymentId={}",
                            def.getProcessCode(), result.getVersion(), result.getDeploymentId());
                } catch (Exception e) {
                    // 单条失败不中断其它流程：可能是节点配置非法等，记日志交人工排查
                    log.warn("[process-auto-deploy] 部署流程 {} 失败（不影响启动）: {}",
                            def.getProcessCode(), e.toString());
                }
            }
            log.info("[process-auto-deploy] 完成：待部署 {} 条，成功 {} 条", pending.size(), ok);
        } catch (Exception e) {
            // 整体异常只记日志，绝不让应用启动失败
            log.warn("[process-auto-deploy] 自动部署执行失败（不影响启动）: {}", e.toString());
        }
    }
}
