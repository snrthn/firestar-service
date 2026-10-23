package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.dto.NotifyCommand;
import com.firestar.dto.ReceivableVO;
import com.firestar.dto.ShipmentConfirmReceiveReq;
import com.firestar.dto.ShipmentCreateReq;
import com.firestar.dto.ShipmentLineCreateReq;
import com.firestar.dto.ShipmentLineReceiveReq;
import com.firestar.dto.ShipmentLineVO;
import com.firestar.dto.ShipmentVO;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.dto.TaskInfo;
import com.firestar.entity.DictData;
import com.firestar.entity.FileStorage;
import com.firestar.entity.Item;
import com.firestar.entity.Project;
import com.firestar.entity.Receivable;
import com.firestar.entity.Role;
import com.firestar.entity.Shipment;
import com.firestar.entity.ShipmentLine;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.mapper.DictDataMapper;
import com.firestar.mapper.FileStorageMapper;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.ShipmentLineMapper;
import com.firestar.mapper.ShipmentMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.NotificationService;
import com.firestar.service.ReceivableService;
import com.firestar.service.ShipmentService;
import com.firestar.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 发货业务逻辑实现（决策全冻结 2026-10-23）。
 *
 * <p>关键约定（对齐 ProjectServiceImpl / BaseEntity）：tenant_id / version 不手动 set，
 * 由插件/拦截器管；createdBy 由业务层显式赋值（AuditMetaObjectHandler 不填 createdBy 的既有决策）。</p>
 */
@Slf4j
@Service
public class ShipmentServiceImpl implements ShipmentService {

    /** 发货审批流程 process_code（P3 引擎，一步 ROLE_SALES_MANAGER 节点，决策#1） */
    public static final String SHIPMENT_PROCESS_CODE = "shipment_approval";

    private static final DateTimeFormatter BATCH_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String DICT_SHIPMENT_STATUS = "shipment_status";
    private static final String DICT_SHIPMENT_LINE_STATUS = "shipment_line_status";
    private static final String DICT_RECEIVABLE_STATUS = "receivable_status";
    private static final String DICT_DOC_DIRECTION = "doc_direction";
    private static final String DICT_RECEIVABLE_SOURCE = "receivable_source";
    private static final String ROLE_SALES_MANAGER = "ROLE_SALES_MANAGER";

    private final ShipmentMapper shipmentMapper;
    private final ShipmentLineMapper shipmentLineMapper;
    private final ItemMapper itemMapper;
    private final ProjectMapper projectMapper;
    private final ReceivableMapper receivableMapper;
    private final DictDataMapper dictDataMapper;
    private final FileStorageMapper fileStorageMapper;
    private final WorkflowEngineService engine;
    private final NotificationService notificationService;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final ReceivableService receivableService;

    @Autowired
    public ShipmentServiceImpl(ShipmentMapper shipmentMapper, ShipmentLineMapper shipmentLineMapper,
                               ItemMapper itemMapper, ProjectMapper projectMapper,
                               ReceivableMapper receivableMapper, DictDataMapper dictDataMapper,
                               FileStorageMapper fileStorageMapper,
                               WorkflowEngineService engine, NotificationService notificationService,
                               UserMapper userMapper, RoleMapper roleMapper,
                               UserRoleMapper userRoleMapper, ReceivableService receivableService) {
        this.shipmentMapper = shipmentMapper;
        this.shipmentLineMapper = shipmentLineMapper;
        this.itemMapper = itemMapper;
        this.projectMapper = projectMapper;
        this.receivableMapper = receivableMapper;
        this.dictDataMapper = dictDataMapper;
        this.fileStorageMapper = fileStorageMapper;
        this.engine = engine;
        this.notificationService = notificationService;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.receivableService = receivableService;
    }

    // ===================== 创建 + 发起审批（决策#1） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ShipmentCreateReq req) {
        Long operatorId = currentUserId();
        if (req.getProjectId() == null) {
            throw new IllegalArgumentException("项目不能为空");
        }
        if (req.getLines() == null || req.getLines().isEmpty()) {
            throw new IllegalArgumentException("发货明细不能为空");
        }
        Project project = projectMapper.selectById(req.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("项目不存在：" + req.getProjectId());
        }

        // 逐行校验：超发硬卡（决策#3）+ 超合同金额硬卡（决策#4）+ 累计已发量（乐观锁）
        BigDecimal newAmount = BigDecimal.ZERO;
        List<Item> toUpdateItems = new ArrayList<>();
        for (ShipmentLineCreateReq line : req.getLines()) {
            if (line.getItemId() == null) {
                throw new IllegalArgumentException("明细行缺少货物 ID");
            }
            if (line.getShipmentQty() == null || line.getShipmentQty() <= 0) {
                throw new IllegalArgumentException("发货数量必须为正：" + line.getItemId());
            }
            Item item = itemMapper.selectById(line.getItemId());
            if (item == null) {
                throw new IllegalArgumentException("货物不存在：" + line.getItemId());
            }
            if (!Objects.equals(item.getProjectId(), project.getId())) {
                throw new IllegalArgumentException("货物不属于该项目：" + line.getItemId());
            }
            int shipped = item.getShippedQty() == null ? 0 : item.getShippedQty();
            int remaining = item.getQuantity() - shipped;
            // 超发硬卡（决策#3）
            if (line.getShipmentQty() > remaining) {
                throw new IllegalArgumentException(String.format(
                        "货物「%s」超发：合同量 %d，已发 %d，剩余 %d，本次申请 %d（超 %d）",
                        item.getItemName(), item.getQuantity(), shipped, remaining,
                        line.getShipmentQty(), line.getShipmentQty() - remaining));
            }
            // 超合同金额硬卡（决策#4）：历史已发金额 + 本次金额 不得超过合同金额
            BigDecimal lineAmt = item.getUnitPrice() != null
                    ? item.getUnitPrice().multiply(BigDecimal.valueOf(line.getShipmentQty())) : BigDecimal.ZERO;
            if (project.getContractAmount() != null) {
                BigDecimal shippedAmt = item.getUnitPrice() != null
                        ? item.getUnitPrice().multiply(BigDecimal.valueOf(shipped)) : BigDecimal.ZERO;
                if (shippedAmt.add(lineAmt).compareTo(project.getContractAmount()) > 0) {
                    throw new IllegalArgumentException(String.format(
                            "货物「%s」将导致累计发货金额超过合同金额（合同 %s）",
                            item.getItemName(), project.getContractAmount()));
                }
            }
            // 累计已发量（带 version 乐观锁，并发超发会抛 OptimisticLockException 回滚）
            item.setShippedQty(shipped + line.getShipmentQty());
            toUpdateItems.add(item);
            newAmount = newAmount.add(lineAmt);
        }

        String batchNo = generateBatchNo(project);

        Shipment shipment = new Shipment();
        shipment.setProjectId(project.getId());
        shipment.setBatchNo(batchNo);
        shipment.setShipmentAmount(newAmount);
        shipment.setReceivedAmount(BigDecimal.ZERO);
        shipment.setReturnedAmount(BigDecimal.ZERO);
        shipment.setShipmentStatus("PENDING");
        shipment.setApplicantId(operatorId);
        shipment.setCreatedBy(operatorId);
        shipmentMapper.insert(shipment);

        for (ShipmentLineCreateReq line : req.getLines()) {
            Item item = toUpdateItems.stream().filter(i -> i.getId().equals(line.getItemId()))
                    .findFirst().orElseThrow();
            ShipmentLine sl = new ShipmentLine();
            sl.setShipmentId(shipment.getId());
            sl.setItemId(line.getItemId());
            sl.setShipmentQty(line.getShipmentQty());
            sl.setReceivedQty(0);
            sl.setReturnedQty(0);
            sl.setLineStatus("PENDING");
            sl.setUnitPrice(item.getUnitPrice());
            sl.setLineAmount(item.getUnitPrice() != null
                    ? item.getUnitPrice().multiply(BigDecimal.valueOf(line.getShipmentQty())) : BigDecimal.ZERO);
            shipmentLineMapper.insert(sl);
        }
        // 回写 Item 已发累计（乐观锁生效点）
        for (Item item : toUpdateItems) {
            itemMapper.updateById(item);
        }
        // 拉审批（决策#1）：一步 ROLE_SALES_MANAGER 节点
        Map<String, Object> vars = new HashMap<>();
        vars.put("applicantUsername", currentUsername());
        vars.put("applicantId", operatorId);
        String instanceId = engine.start(SHIPMENT_PROCESS_CODE, String.valueOf(shipment.getId()), vars);
        shipment.setProcessInstanceId(instanceId);
        shipmentMapper.updateById(shipment);

        log.info("[shipment] 创建并发起审批 id={} batchNo={} projectId={} amount={}",
                shipment.getId(), batchNo, project.getId(), newAmount);
        // 提交时通知所有销售经理（决策#2）
        notifySubmitted(project, shipment.getId(), operatorId);
        return shipment.getId();
    }

    // ===================== 标记已发货 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markShipped(Long shipmentId) {
        Long operatorId = currentUserId();
        Shipment s = shipmentMapper.selectById(shipmentId);
        if (s == null) {
            throw new IllegalArgumentException("发货单不存在：" + shipmentId);
        }
        if (!"PENDING".equals(s.getShipmentStatus())) {
            throw new IllegalArgumentException("仅「待发货」状态可标记发货，当前：" + s.getShipmentStatus());
        }
        s.setShipmentStatus("SHIPPED");
        s.setShipmentDate(LocalDateTime.now());
        s.setShippedById(operatorId); // 决策#2 澄清：发货人 = 操作人自身
        s.setUpdatedBy(operatorId);
        shipmentMapper.updateById(s);
        log.info("[shipment] 标记已发货 id={} by={}", shipmentId, operatorId);
    }

    // ===================== 标记客户收货（决策#2/#6） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceive(ShipmentConfirmReceiveReq req) {
        Long operatorId = currentUserId();
        // 证据留痕硬约束（决策#6）：内部销售代确认必须上传签收证据
        if (req.getProofFileIds() == null || req.getProofFileIds().isEmpty()) {
            throw new IllegalArgumentException("客户已收货必须上传签收证据（bizType=shipment_receive_proof）");
        }
        Shipment s = shipmentMapper.selectById(req.getShipmentId());
        if (s == null) {
            throw new IllegalArgumentException("发货单不存在：" + req.getShipmentId());
        }
        if (!"SHIPPED".equals(s.getShipmentStatus())) {
            throw new IllegalArgumentException("仅「已发货」状态可标记客户收货，当前：" + s.getShipmentStatus());
        }
        List<ShipmentLine> lines = shipmentLineMapper.selectList(new LambdaQueryWrapper<ShipmentLine>()
                .eq(ShipmentLine::getShipmentId, s.getId()));
        Map<Long, ShipmentLine> lineByItem = lines.stream()
                .collect(Collectors.toMap(ShipmentLine::getItemId, l -> l));
        boolean allReceived = true;
        for (ShipmentLineReceiveReq r : req.getLines()) {
            ShipmentLine line = lineByItem.get(r.getItemId());
            if (line == null) {
                throw new IllegalArgumentException("收货行不属于该发货单：" + r.getItemId());
            }
            if (r.getReceivedQty() == null || r.getReceivedQty() < 0) {
                throw new IllegalArgumentException("收货数量非法：" + r.getItemId());
            }
            int maxRemain = line.getShipmentQty() - line.getReceivedQty();
            if (r.getReceivedQty() > maxRemain) {
                throw new IllegalArgumentException(String.format(
                        "货物收货量超出可收：已发 %d，已收 %d，本次最多 %d",
                        line.getShipmentQty(), line.getReceivedQty(), maxRemain));
            }
            line.setReceivedQty(line.getReceivedQty() + r.getReceivedQty());
            line.setLineStatus(Objects.equals(line.getReceivedQty(), line.getShipmentQty())
                    ? "FULL_RECEIVED" : "PARTIAL_RECEIVED");
            shipmentLineMapper.updateById(line);
            if (!Objects.equals(line.getReceivedQty(), line.getShipmentQty())) {
                allReceived = false;
            }
        }
        s.setShipmentStatus(allReceived ? "FULL_RECEIVED" : "PARTIAL_RECEIVED");
        s.setConfirmedById(operatorId);
        s.setUpdatedBy(operatorId);
        // 联动生成应收单（决策#2：客户已收货时点，金额 = Σ receivedQty × unitPrice）
        Long receivableId = receivableService.createFromShipment(s.getId(), operatorId);
        s.setReceivableId(receivableId);
        shipmentMapper.updateById(s);
        log.info("[shipment] 客户收货 id={} status={} receivableId={}", s.getId(), s.getShipmentStatus(), receivableId);
    }

    // ===================== 审批通过回调（决策#2） =====================

    @Override
    public void completeTask(String taskId, Map<String, Object> variables) {
        // 审批意见从 variables.comment 透传至引擎评论（2026-10-23：审批中心统一弹窗传留言，
        // 此前 comment 恒 null 导致时间线看不到审批意见）
        String comment = variables == null ? null : Objects.toString(variables.get("comment"), null);
        // 时序双保险：complete 前先拿 businessKey（参考 ProjectServiceImpl.completeTask）
        String bizKeyUpfront = engine.businessKeyOfTask(taskId);
        String instanceId = engine.complete(taskId, variables, comment);
        List<TaskInfo> next = engine.listTasks(instanceId);
        if (next.isEmpty()) {
            // 一步审批流程走完：回写发货单（保持 PENDING=待发货）+ 通知审批人引导发货（决策#2）
            String bizKey = bizKeyUpfront != null ? bizKeyUpfront : engine.getBusinessKey(instanceId);
            Shipment s = (bizKey != null) ? shipmentMapper.selectById(Long.parseLong(bizKey)) : null;
            if (s != null) {
                // 状态创建时即 PENDING（待发货），此处无需改状态，仅发通知引导发货
                notifyApproved(s, currentUserId());
                log.info("[shipment] 审批通过回写并通知 id={}", s.getId());
            } else {
                log.warn("[shipment] 审批通过但未回写：bizKey={} 找不到发货单 instanceId={}", bizKey, instanceId);
            }
        }
        // 一步节点无后续任务，next 恒空
    }

    // ===================== 审批驳回（决策#2/#6：单节点无退回目标） =====================

    @Override
    public void rejectTask(String taskId, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("驳回理由不能为空");
        }
        // 候选人校验（引擎层）：terminate 路径不走 complete/reject，需显式过闸
        engine.assertTaskHandleable(taskId);
        // 审批中任务仍在 runtime，businessKeyOfTask 必中；拿不到（任务已结束）则安全返回
        String bizKey = engine.businessKeyOfTask(taskId);
        if (bizKey == null) {
            log.warn("[shipment] 驳回时取不到业务键（任务可能已结束）taskId={}", taskId);
            return;
        }
        Shipment s = shipmentMapper.selectById(Long.parseLong(bizKey));
        if (s == null) {
            log.warn("[shipment] 驳回时找不到发货单 bizKey={} taskId={}", bizKey, taskId);
            return;
        }
        // 单节点审批：无「退回申请人」节点，直接终结流程实例（保留 processInstanceId 便于排查）
        if (s.getProcessInstanceId() != null) {
            engine.terminate(s.getProcessInstanceId(), "发货申请被退回：" + comment);
        }
        s.setShipmentStatus("REJECTED");
        s.setUpdatedBy(currentUserId());
        shipmentMapper.updateById(s);
        log.info("[shipment] 审批驳回回写 id={} comment={}", s.getId(), comment);
        // 通知申请人（= 提交发货单的销售经理，决策#6 同人逻辑）
        notifyRejected(s, currentUsername(), comment);
    }

    /** 审批驳回时通知申请人（决策#6：发货人=申请人自身） */
    private void notifyRejected(Shipment s, String approver, String comment) {
        if (s.getApplicantId() == null) {
            return;
        }
        Project p = projectMapper.selectById(s.getProjectId());
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("SHIPMENT_REJECTED");
        cmd.setReceivers(List.of(s.getApplicantId()));
        cmd.setBizType("SHIPMENT");
        cmd.setBizId(String.valueOf(s.getId()));
        cmd.setLinkUrl("/shipment");
        cmd.setDedupKey("SHIPMENT_REJECTED:SHIPMENT:" + s.getId() + ":" + approver);
        cmd.setVars(Map.of(
                "batchNo", s.getBatchNo() == null ? "" : s.getBatchNo(),
                "projectName", p != null && p.getProjectName() != null ? p.getProjectName() : "",
                "approver", approver == null ? "" : approver,
                "comment", comment == null ? "" : comment));
        cmd.setOperatorId(s.getApplicantId());
        notificationService.notify(cmd);
    }

    // ===================== 查询 =====================

    @Override
    public IPage<ShipmentVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                                 String projectNo, String projectName, String status) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 500L);
        String noKw = projectNo == null || projectNo.isBlank() ? null : projectNo.trim();
        String nameKw = projectName == null || projectName.isBlank() ? null : projectName.trim();
        String statusEq = status == null || status.isBlank() ? null : status.trim();

        IPage<Shipment> page = shipmentMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<Shipment>()
                        .like(noKw != null, Shipment::getBatchNo, noKw)
                        .eq(statusEq != null, Shipment::getShipmentStatus, statusEq)
                        .orderByDesc(Shipment::getCreatedAt));
        return page.convert(s -> toVO(s, dictMaps(), userNamesOf(page.getRecords())));
    }

    @Override
    public ShipmentVO detail(Long shipmentId) {
        Shipment s = shipmentMapper.selectById(shipmentId);
        if (s == null) {
            throw new IllegalArgumentException("发货单不存在：" + shipmentId);
        }
        return toVO(s, dictMaps(), userNamesOf(List.of(s)));
    }

    @Override
    public List<TimelineNodeVO> timeline(Long shipmentId) {
        return engine.listTimelineByBusinessKey(String.valueOf(shipmentId));
    }

    // ===================== 私有 =====================

    private ShipmentVO toVO(Shipment s, DictMaps dicts, Map<Long, String> userNames) {
        ShipmentVO vo = new ShipmentVO();
        vo.setId(s.getId());
        vo.setProjectId(s.getProjectId());
        vo.setBatchNo(s.getBatchNo());
        vo.setShipmentDate(s.getShipmentDate());
        vo.setShipmentAmount(s.getShipmentAmount());
        vo.setReceivedAmount(s.getReceivedAmount());
        vo.setReturnedAmount(s.getReturnedAmount());
        vo.setShipmentStatus(s.getShipmentStatus());
        DictData st = dicts.shipmentStatus.get(s.getShipmentStatus());
        vo.setShipmentStatusName(st != null ? st.getDictLabel() : null);
        vo.setShipmentStatusStyle(st != null ? st.getStyle() : null);
        vo.setReceivableId(s.getReceivableId());
        vo.setProcessInstanceId(s.getProcessInstanceId());
        vo.setApplicantId(s.getApplicantId());
        vo.setApplicantName(s.getApplicantId() != null ? userNames.get(s.getApplicantId()) : null);
        vo.setShippedById(s.getShippedById());
        vo.setShippedByName(s.getShippedById() != null ? userNames.get(s.getShippedById()) : null);
        vo.setConfirmedById(s.getConfirmedById());
        vo.setConfirmedByName(s.getConfirmedById() != null ? userNames.get(s.getConfirmedById()) : null);
        vo.setCreatedAt(s.getCreatedAt());

        // 项目信息
        Project p = projectMapper.selectById(s.getProjectId());
        if (p != null) {
            vo.setProjectNo(p.getProjectNo());
            vo.setProjectName(p.getProjectName());
            vo.setCustomerName(p.getCustomerName());
        }
        // 明细行
        List<ShipmentLine> lines = shipmentLineMapper.selectList(new LambdaQueryWrapper<ShipmentLine>()
                .eq(ShipmentLine::getShipmentId, s.getId()));
        List<ShipmentLineVO> lineVos = new ArrayList<>(lines.size());
        Map<Long, Item> itemCache = new HashMap<>();
        for (ShipmentLine line : lines) {
            ShipmentLineVO lv = new ShipmentLineVO();
            lv.setId(line.getId());
            lv.setShipmentId(line.getShipmentId());
            lv.setItemId(line.getItemId());
            Item item = itemCache.computeIfAbsent(line.getItemId(), itemMapper::selectById);
            lv.setItemName(item != null ? item.getItemName() : null);
            lv.setSpecModel(item != null ? item.getSpecModel() : null);
            lv.setUnit(item != null ? item.getUnit() : null);
            lv.setShipmentQty(line.getShipmentQty());
            lv.setReceivedQty(line.getReceivedQty());
            lv.setReturnedQty(line.getReturnedQty());
            lv.setLineStatus(line.getLineStatus());
            DictData lmeta = dicts.shipmentLineStatus.get(line.getLineStatus());
            lv.setLineStatusName(lmeta != null ? lmeta.getDictLabel() : null);
            lv.setLineStatusStyle(lmeta != null ? lmeta.getStyle() : null);
            lv.setUnitPrice(line.getUnitPrice());
            lv.setLineAmount(line.getLineAmount());
            lv.setRemainingCanReceive(line.getShipmentQty() - line.getReceivedQty() - line.getReturnedQty());
            lineVos.add(lv);
        }
        vo.setLines(lineVos);
        // 关联应收单
        if (s.getReceivableId() != null) {
            Receivable r = receivableMapper.selectById(s.getReceivableId());
            if (r != null) {
                vo.setReceivable(toReceivableVO(r, dicts));
            }
        }
        // 当前登录用户在流程实例上的待办任务（审批中才有；审批通过/驳回后实例结束 → 无 → 详情页据此切换「通过/驳回」与「标记发货」）
        if (s.getProcessInstanceId() != null && "PENDING".equals(s.getShipmentStatus())) {
            List<TaskInfo> tasks = engine.listTasks(s.getProcessInstanceId());
            if (tasks != null && !tasks.isEmpty()) {
                vo.setMyPendingTaskId(tasks.get(0).getTaskId());
            }
        }
        // 签收证据文件 ID 列表（决策#6：confirmedById 非空时按 bizType+bizId 从文件表回填；
        // 前端据此调文件接口拉取签收证据详情/下载，避免详情页证据列表为空）
        if (s.getConfirmedById() != null) {
            List<FileStorage> proofs = fileStorageMapper.selectList(new LambdaQueryWrapper<FileStorage>()
                    .eq(FileStorage::getBizType, "shipment_receive_proof")
                    .eq(FileStorage::getBizId, s.getId())
                    .orderByAsc(FileStorage::getId));
            if (!proofs.isEmpty()) {
                vo.setProofFileIds(proofs.stream().map(FileStorage::getId).collect(Collectors.toList()));
            }
        }
        return vo;
    }

    private ReceivableVO toReceivableVO(Receivable r, DictMaps dicts) {
        ReceivableVO rv = new ReceivableVO();
        rv.setId(r.getId());
        rv.setReceivableNo(r.getReceivableNo());
        rv.setProjectId(r.getProjectId());
        rv.setShipmentId(r.getShipmentId());
        rv.setDocDirection(r.getDocDirection());
        DictData dmeta = dicts.docDirection.get(r.getDocDirection());
        rv.setDocDirectionName(dmeta != null ? dmeta.getDictLabel() : null);
        rv.setReceivableAmount(r.getReceivableAmount());
        rv.setWrittenOffAmount(r.getWrittenOffAmount());
        rv.setUnwrittenOffAmount(r.getUnwrittenOffAmount());
        rv.setInvoicedAmount(r.getInvoicedAmount());
        rv.setUninvoicedAmount(r.getUninvoicedAmount());
        rv.setReceivableDate(r.getReceivableDate());
        rv.setStatus(r.getStatus());
        DictData smeta = dicts.receivableStatus.get(r.getStatus());
        rv.setStatusName(smeta != null ? smeta.getDictLabel() : null);
        rv.setStatusStyle(smeta != null ? smeta.getStyle() : null);
        rv.setSourceType(r.getSourceType());
        DictData srcMeta = dicts.receivableSource.get(r.getSourceType());
        rv.setSourceTypeName(srcMeta != null ? srcMeta.getDictLabel() : null);
        return rv;
    }

    /** 三种字典 map 一次查询复用 */
    private DictMaps dictMaps() {
        return new DictMaps(
                dictMap(DICT_SHIPMENT_STATUS), dictMap(DICT_SHIPMENT_LINE_STATUS),
                dictMap(DICT_RECEIVABLE_STATUS), dictMap(DICT_DOC_DIRECTION), dictMap(DICT_RECEIVABLE_SOURCE));
    }

    private Map<String, DictData> dictMap(String typeCode) {
        List<DictData> rows = dictDataMapper.selectList(new LambdaQueryWrapper<DictData>()
                .eq(DictData::getTypeCode, typeCode).eq(DictData::getStatus, "ENABLED"));
        Map<String, DictData> map = new HashMap<>();
        for (DictData d : rows) {
            map.put(d.getDictValue(), d);
        }
        return map;
    }

    /** 用户 id → 展示名（nickname 优先，批量一次查，避免 N+1） */
    private Map<Long, String> userNamesOf(List<Shipment> shipments) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Shipment s : shipments) {
            if (s.getApplicantId() != null) ids.add(s.getApplicantId());
            if (s.getShippedById() != null) ids.add(s.getShippedById());
            if (s.getConfirmedById() != null) ids.add(s.getConfirmedById());
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .in(User::getId, ids).select(User::getId, User::getNickname, User::getUsername));
        Map<Long, String> map = new HashMap<>();
        for (User u : users) {
            String name = (u.getNickname() != null && !u.getNickname().isBlank())
                    ? u.getNickname() : u.getUsername();
            map.put(u.getId(), name);
        }
        return map;
    }

    private String generateBatchNo(Project project) {
        long count = shipmentMapper.selectCount(new LambdaQueryWrapper<Shipment>()
                .eq(Shipment::getProjectId, project.getId()));
        String no = project.getProjectNo() == null || project.getProjectNo().isBlank()
                ? "NA" : project.getProjectNo();
        return String.format("FH-%s-%03d", no, count + 1);
    }

    /** 提交时通知所有销售经理（决策#2：通知潜在审批人） */
    private void notifySubmitted(Project project, Long shipmentId, Long operatorId) {
        List<Long> receivers = resolveRoleUserIds(ROLE_SALES_MANAGER);
        if (receivers.isEmpty()) {
            // 2026-10-23 虎哥实锤：此前静默 return 不可观测（无销售经理用户时通知凭空消失）
            log.warn("[shipment] 提交审批通知无接收人：role={} 下无用户，请检查 sys_user_role 角色绑定 shipmentId={}",
                    ROLE_SALES_MANAGER, shipmentId);
            return;
        }
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("SHIPMENT_SUBMITTED");
        cmd.setReceivers(receivers);
        cmd.setBizType("SHIPMENT");
        cmd.setBizId(String.valueOf(shipmentId));
        // 通知的是潜在审批人：链接直达审批中心待办（2026-10-23 虎哥：此前跳 /shipment 路径不对）
        cmd.setLinkUrl("/approval");
        cmd.setDedupKey("SHIPMENT_SUBMITTED:SHIPMENT:" + shipmentId);
        cmd.setVars(Map.of("projectName", project.getProjectName() == null ? "" : project.getProjectName()));
        cmd.setOperatorId(operatorId);
        notificationService.notify(cmd);
    }

    /** 审批通过时通知审批人（= 当前操作人 = 发货人，决策#2 澄清同人）引导发货 */
    private void notifyApproved(Shipment s, Long approverId) {
        if (approverId == null) {
            return;
        }
        Project p = projectMapper.selectById(s.getProjectId());
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("SHIPMENT_APPROVED_GUIDE");
        cmd.setReceivers(List.of(approverId));
        cmd.setBizType("SHIPMENT");
        cmd.setBizId(String.valueOf(s.getId()));
        cmd.setLinkUrl("/shipment");
        cmd.setDedupKey("SHIPMENT_APPROVED_GUIDE:SHIPMENT:" + s.getId() + ":" + approverId);
        cmd.setVars(Map.of(
                "batchNo", s.getBatchNo() == null ? "" : s.getBatchNo(),
                "projectName", p != null && p.getProjectName() != null ? p.getProjectName() : ""));
        cmd.setOperatorId(approverId);
        notificationService.notify(cmd);
    }

    /** 角色 code → 全员 userId（复用 ProjectServiceImpl.resolveReceiverIds 的 ROLE 候选组解析） */
    private List<Long> resolveRoleUserIds(String roleCode) {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleCode, roleCode).select(Role::getId));
        if (role == null) {
            return Collections.emptyList();
        }
        List<UserRole> urs = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRoleId, role.getId()));
        return urs.stream().map(UserRole::getUserId).collect(Collectors.toList());
    }

    private Long currentUserId() {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, currentUsername()).select(User::getId));
        return u == null ? null : u.getId();
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    /** 字典 map 容器（避免 toVO 多次查库） */
    private static final class DictMaps {
        final Map<String, DictData> shipmentStatus;
        final Map<String, DictData> shipmentLineStatus;
        final Map<String, DictData> receivableStatus;
        final Map<String, DictData> docDirection;
        final Map<String, DictData> receivableSource;

        DictMaps(Map<String, DictData> shipmentStatus, Map<String, DictData> shipmentLineStatus,
                 Map<String, DictData> receivableStatus, Map<String, DictData> docDirection,
                 Map<String, DictData> receivableSource) {
            this.shipmentStatus = shipmentStatus;
            this.shipmentLineStatus = shipmentLineStatus;
            this.receivableStatus = receivableStatus;
            this.docDirection = docDirection;
            this.receivableSource = receivableSource;
        }
    }
}
