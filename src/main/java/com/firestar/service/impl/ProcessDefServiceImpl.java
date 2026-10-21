package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.dto.DeployResult;
import com.firestar.dto.NodeCfg;
import com.firestar.dto.ProcessDefVersionVO;
import com.firestar.entity.ProcessDef;
import com.firestar.mapper.ProcessDefMapper;
import com.firestar.service.ProcessDefService;
import com.firestar.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.BpmnModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 流程定义服务实现。
 *
 * <p>节点配置以 {@code List<NodeCfg>} 存于 {@code sys_process_def.node_list}（JSON 列），
 * 部署时反查本行 nodeList → 调门面 generateBpmn → deploy → 回写版本信息。</p>
 */
@Slf4j
@Service
public class ProcessDefServiceImpl implements ProcessDefService {

    /** 分页默认每页条数（前端分页器默认规格一致，同 RoleServiceImpl） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    /** 单页上限，与 MybatisPlusConfig.MAX_PAGE_SIZE 对齐 */
    private static final long MAX_PAGE_SIZE = 500L;

    private final ProcessDefMapper mapper;
    private final WorkflowEngineService engine;

    @Autowired
    public ProcessDefServiceImpl(ProcessDefMapper mapper, WorkflowEngineService engine) {
        this.mapper = mapper;
        this.engine = engine;
    }

    @Override
    public List<ProcessDef> list() {
        return mapper.selectList(new LambdaQueryWrapper<ProcessDef>()
                .orderByDesc(ProcessDef::getCreatedAt));
    }

    @Override
    public IPage<ProcessDef> page(Integer pageNum, Integer pageSize,
                                  String processCode, String processName, String status) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        // trim 落局部变量：wrapper.like(condition, column, kw) 参数渴求求值，condition=false 时也会执行 trim()
        String codeKw = processCode == null || processCode.isBlank() ? null : processCode.trim();
        String nameKw = processName == null || processName.isBlank() ? null : processName.trim();
        String statusEq = status == null || status.isBlank() ? null : status.trim();
        return mapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<ProcessDef>()
                        .like(codeKw != null, ProcessDef::getProcessCode, codeKw)
                        .like(nameKw != null, ProcessDef::getProcessName, nameKw)
                        .eq(statusEq != null, ProcessDef::getStatus, statusEq)
                        .orderByDesc(ProcessDef::getCreatedAt));
    }

    @Override
    public ProcessDef detail(Long id) {
        return mapper.selectById(id);
    }

    @Override
    public ProcessDef create(ProcessDef def) {
        Long cnt = mapper.selectCount(new LambdaQueryWrapper<ProcessDef>()
                .eq(ProcessDef::getProcessCode, def.getProcessCode()));
        if (cnt != null && cnt > 0) {
            throw new IllegalArgumentException("流程编码已存在：" + def.getProcessCode());
        }
        if (def.getStatus() == null || def.getStatus().isEmpty()) {
            def.setStatus("ENABLED");
        }
        mapper.insert(def);
        return def;
    }

    @Override
    public ProcessDef update(Long id, ProcessDef def) {
        ProcessDef exist = mapper.selectById(id);
        if (exist == null) {
            throw new IllegalArgumentException("流程定义不存在：" + id);
        }
        if (def.getProcessName() != null) {
            exist.setProcessName(def.getProcessName());
        }
        if (def.getRemark() != null) {
            exist.setRemark(def.getRemark());
        }
        if (def.getNodeList() != null) {
            exist.setNodeList(def.getNodeList());
        }
        mapper.updateById(exist);
        return exist;
    }

    @Override
    public DeployResult deploy(Long id) {
        ProcessDef def = mapper.selectById(id);
        if (def == null) {
            throw new IllegalArgumentException("流程定义不存在：" + id);
        }
        List<NodeCfg> nodes = def.getNodeList();
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("节点未配置，无法部署：" + def.getProcessCode());
        }
        BpmnModel model = engine.generateBpmn(def.getProcessCode(), def.getProcessName(), nodes);
        DeployResult result = engine.deploy(def.getProcessCode(), def.getProcessName(), model);
        def.setFlowableDeploymentId(result.getDeploymentId());
        def.setDefVersion(result.getVersion());
        mapper.updateById(def);
        log.info("[process-def] 部署成功 id={} code={} version={}", id, def.getProcessCode(), result.getVersion());
        return result;
    }

    @Override
    public ProcessDef toggleStatus(Long id) {
        ProcessDef def = mapper.selectById(id);
        if (def == null) {
            throw new IllegalArgumentException("流程定义不存在：" + id);
        }
        def.setStatus("ENABLED".equals(def.getStatus()) ? "DISABLED" : "ENABLED");
        mapper.updateById(def);
        return def;
    }

    @Override
    public List<ProcessDefVersionVO> versions(Long id) {
        ProcessDef def = mapper.selectById(id);
        if (def == null) {
            throw new IllegalArgumentException("流程定义不存在：" + id);
        }
        return engine.listVersions(def.getProcessCode());
    }
}
