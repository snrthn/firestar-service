package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.DeployResult;
import com.firestar.dto.ProcessDefVersionVO;
import com.firestar.entity.ProcessDef;

import java.util.List;

/**
 * 流程定义业务服务（Step 2 配置节点）。
 *
 * <p>职责：流程定义 CRUD + 节点配置保存 + 部署（经 WorkflowEngineService 门面）+ 版本查询。
 * 不持有任何 Flowable 类型，所有引擎交互委托给 {@code WorkflowEngineService}。</p>
 */
public interface ProcessDefService {

    /** 列表（按创建时间倒序） */
    List<ProcessDef> list();

    /**
     * 分页查询（对齐 /system/role/page 范式）。
     *
     * @param pageNum      页码，缺省 1
     * @param pageSize     每页条数，缺省 10，上限 500
     * @param processCode  流程编码，模糊匹配
     * @param processName  流程名称，模糊匹配
     * @param status       状态，精确匹配（ENABLED / DISABLED）
     */
    IPage<ProcessDef> page(Integer pageNum, Integer pageSize,
                           String processCode, String processName, String status);

    /** 详情（含 nodeList） */
    ProcessDef detail(Long id);

    /** 新建（processCode 唯一校验；status 默认 ENABLED） */
    ProcessDef create(ProcessDef def);

    /** 更新元数据 + 节点配置（不触发部署，部署需显式调 deploy） */
    ProcessDef update(Long id, ProcessDef def);

    /** 部署：用已存节点配置生成 BPMN 并部署，回写 deploymentId / defVersion */
    DeployResult deploy(Long id);

    /** 停用 ↔ 启用切换（status 在 ENABLED/DISABLED 间 toggle） */
    ProcessDef toggleStatus(Long id);

    /** 历史版本列表 */
    List<ProcessDefVersionVO> versions(Long id);
}
