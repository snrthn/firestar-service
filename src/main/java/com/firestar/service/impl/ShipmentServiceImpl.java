package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.common.DataScopeHelper;
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
import com.firestar.entity.Customer;
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
import com.firestar.mapper.CustomerMapper;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    private static final String DICT_SHIPMENT_APPROVAL_STATUS = "shipment_approval_status";
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
    /** 客户主表（V68）：V72 起 fin_project 不再存客户名，展示名需按 customer_id 联表翻译 */
    private final CustomerMapper customerMapper;

    @Autowired
    public ShipmentServiceImpl(ShipmentMapper shipmentMapper, ShipmentLineMapper shipmentLineMapper,
                               ItemMapper itemMapper, ProjectMapper projectMapper,
                               ReceivableMapper receivableMapper, DictDataMapper dictDataMapper,
                               FileStorageMapper fileStorageMapper,
                               WorkflowEngineService engine, NotificationService notificationService,
                               UserMapper userMapper, RoleMapper roleMapper,
                               UserRoleMapper userRoleMapper, ReceivableService receivableService,
                               CustomerMapper customerMapper) {
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
        this.customerMapper = customerMapper;
    }

    // ===================== 创建（仅落草稿；审批由详情面板 submit 触发） =====================

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

        // 逐行校验 + 占用（create/update 共用同一份校验，避免两份逻辑漂移）
        List<Item> toUpdateItems = new ArrayList<>();
        BigDecimal newAmount = validateAndOccupy(project, req.getLines(), toUpdateItems);

        String batchNo = generateBatchNo(project);

        Shipment shipment = new Shipment();
        shipment.setProjectId(project.getId());
        shipment.setBatchNo(batchNo);
        shipment.setShipmentAmount(newAmount);
        shipment.setReceivedAmount(BigDecimal.ZERO);
        shipment.setReturnedAmount(BigDecimal.ZERO);
        shipment.setShipmentStatus("PENDING");
        // 草稿态：审批由详情面板「提交审批」触发（2026-10-26 虎哥：不再新建即发审批，
        // 否则会出现「有编辑入口的同时审批流已发出」的不合规流程）
        shipment.setApprovalStatus("DRAFT");
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
        // 草稿态创建完成（审批流程在 submit() 中由详情面板触发）
        log.info("[shipment] 创建草稿 id={} batchNo={} projectId={} amount={}",
                shipment.getId(), batchNo, project.getId(), newAmount);
        return shipment.getId();
    }

    // ===================== 提交审批（详情面板触发；仅草稿/驳回可提） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id, String username, boolean isAdmin) {
        Long operatorId = currentUserId();
        Shipment s = shipmentMapper.selectById(id);
        if (s == null) {
            throw new IllegalArgumentException("发货单不存在：" + id);
        }
        // 归属校验（与 update/withdraw 同口径）：仅申请人本人或 ROLE_ADMIN
        if (!isAdmin && !Objects.equals(s.getApplicantId(), operatorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发货单不存在或无权操作：" + id);
        }
        // 仅草稿 / 驳回可提交审批（与立项 submit 同口径；APPROVING/APPROVED/CANCELLED 不可重提）
        if (!("DRAFT".equals(s.getApprovalStatus()) || "REJECTED".equals(s.getApprovalStatus()))) {
            throw new IllegalArgumentException("仅草稿或驳回状态可提交审批，当前：" + s.getApprovalStatus());
        }
        // 草稿即占用货物额度（create/update 已占用），此处仅起流程，不再重复占用
        Project project = projectMapper.selectById(s.getProjectId());
        Map<String, Object> vars = new HashMap<>();
        vars.put("applicantUsername", currentUsername());
        vars.put("applicantId", operatorId);
        String instanceId = engine.start(SHIPMENT_PROCESS_CODE, String.valueOf(s.getId()), vars);
        s.setProcessInstanceId(instanceId);
        s.setApprovalStatus("APPROVING");
        s.setUpdatedBy(operatorId);
        shipmentMapper.updateById(s);
        log.info("[shipment] 提交审批 id={} batchNo={} instanceId={}", s.getId(), s.getBatchNo(), instanceId);
        notifySubmitted(project, s.getId(), operatorId);
    }

    /**
     * 明细行逐行校验 + 占用额度（create / update 共用一份，避免两份校验逻辑漂移）。
     *
     * <p>校验项：货物存在且属于本项目、数量为正、超发硬卡（决策#3）、超合同金额硬卡（决策#4）。
     * 占用：{@code item.shippedQty += 本次数量}（带 version 乐观锁，并发超发抛 OptimisticLockException 回滚）。
     *
     * <p>⚠️ 占用发生在「申请创建时」而非「实际发货时」——故任何让申请失效的动作（撤回 / 编辑减量）
     * 都必须走 {@link #releaseItemOccupancy} 退还，否则额度被永久占用。</p>
     *
     * @param toUpdateItems 出参：待回写的 Item（调用方统一 updateById）
     * @return 本次发货总金额
     */
    private BigDecimal validateAndOccupy(Project project, List<ShipmentLineCreateReq> lines,
                                         List<Item> toUpdateItems) {
        BigDecimal newAmount = BigDecimal.ZERO;
        for (ShipmentLineCreateReq line : lines) {
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
            if (line.getShipmentQty() > remaining) {
                throw new IllegalArgumentException(String.format(
                        "货物「%s」超发：合同量 %d，已发 %d，剩余 %d，本次申请 %d（超 %d）",
                        item.getItemName(), item.getQuantity(), shipped, remaining,
                        line.getShipmentQty(), line.getShipmentQty() - remaining));
            }
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
            item.setShippedQty(shipped + line.getShipmentQty());
            toUpdateItems.add(item);
            newAmount = newAmount.add(lineAmt);
        }
        return newAmount;
    }

    /** 退还某发货单全部明细行已占用的货物额度（撤回 / 编辑前调用） */
    private void releaseItemOccupancy(Long shipmentId) {
        List<ShipmentLine> lines = shipmentLineMapper.selectList(new LambdaQueryWrapper<ShipmentLine>()
                .eq(ShipmentLine::getShipmentId, shipmentId));
        for (ShipmentLine ln : lines) {
            Item item = itemMapper.selectById(ln.getItemId());
            if (item == null) {
                continue;
            }
            int shipped = item.getShippedQty() == null ? 0 : item.getShippedQty();
            int back = ln.getShipmentQty() == null ? 0 : ln.getShipmentQty();
            item.setShippedQty(Math.max(0, shipped - back));
            itemMapper.updateById(item);
        }
    }

    // ===================== 编辑（2026-10-23 虎哥：发货单需要编辑入口） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ShipmentCreateReq req, String username, boolean isAdmin) {
        Long operatorId = currentUserId();
        Shipment s = shipmentMapper.selectById(id);
        if (s == null) {
            throw new IllegalArgumentException("发货单不存在：" + id);
        }
        // 归属校验（2026-10-24 安全债修复）：写类仅申请人本人或 ROLE_ADMIN，不放开审批人
        if (!isAdmin && !Objects.equals(s.getApplicantId(), operatorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发货单不存在或无权操作：" + id);
        }
        // 仅「草稿 / 驳回」可编辑：审批未进入终态时改内容不牵动下游（不占实际出入库/应收）；
        // 审批中(APPROVING)/已通过(APPROVED)/已撤销(CANCELLED) 改内容会与审批结论、库存、应收冲突。
        // 项目不允许更换（换项目等于换合同，应新建）。
        // （虎哥 2026-10-26：原按 shipmentStatus==PENDING 判定会放行审批中单；改为按审批态锁定）
        if (!("DRAFT".equals(s.getApprovalStatus()) || "REJECTED".equals(s.getApprovalStatus()))) {
            throw new IllegalArgumentException("仅草稿或驳回状态可编辑，当前审批状态：" + s.getApprovalStatus());
        }
        if (req.getProjectId() == null) {
            throw new IllegalArgumentException("项目不能为空");
        }
        if (!Objects.equals(req.getProjectId(), s.getProjectId())) {
            throw new IllegalArgumentException("不允许更换项目，请新建发货单");
        }
        if (req.getLines() == null || req.getLines().isEmpty()) {
            throw new IllegalArgumentException("发货明细不能为空");
        }
        Project project = projectMapper.selectById(req.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("项目不存在：" + req.getProjectId());
        }
        // ① 先退还旧明细行的占用，② 再按新明细行校验并占用——顺序不可颠倒，
        // 否则「减量编辑」会因自己的旧占用而误判超发（remaining 被自己吃掉）
        releaseItemOccupancy(id);
        List<Item> toUpdateItems = new ArrayList<>();
        BigDecimal newAmount = validateAndOccupy(project, req.getLines(), toUpdateItems);

        shipmentLineMapper.delete(new LambdaQueryWrapper<ShipmentLine>().eq(ShipmentLine::getShipmentId, id));
        for (ShipmentLineCreateReq line : req.getLines()) {
            Item item = toUpdateItems.stream().filter(i -> i.getId().equals(line.getItemId()))
                    .findFirst().orElseThrow();
            ShipmentLine sl = new ShipmentLine();
            sl.setShipmentId(id);
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
        for (Item item : toUpdateItems) {
            itemMapper.updateById(item);
        }
        s.setShipmentAmount(newAmount);
        s.setUpdatedBy(operatorId);
        shipmentMapper.updateById(s);
        log.info("[shipment] 编辑发货单 id={} amount={} user={}", id, newAmount, currentUsername());
    }

    // ===================== 标记已发货 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markShipped(Long shipmentId, String username, boolean isAdmin) {
        Long operatorId = currentUserId();
        Shipment s = shipmentMapper.selectById(shipmentId);
        if (s == null) {
            throw new IllegalArgumentException("发货单不存在：" + shipmentId);
        }
        // 归属校验（2026-10-24 安全债修复）：写类仅申请人本人或 ROLE_ADMIN，不放开审批人
        if (!isAdmin && !Objects.equals(s.getApplicantId(), operatorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发货单不存在或无权操作：" + shipmentId);
        }
        if (!"PENDING".equals(s.getShipmentStatus())) {
            throw new IllegalArgumentException("仅「待发货」状态可标记发货，当前：" + s.getShipmentStatus());
        }
        // 审批闸门（2026-10-24 虎哥补洞）：shipment_status 审批期间一直是 PENDING，
        // 只查状态拦不住「审批没批完就发货」——必须叠加 approval_status=APPROVED 判定，
        // 否则标记发货+标记收货可整条绕过审批链（决策#1/#2：审批通过后才发货）
        if (!"APPROVED".equals(s.getApprovalStatus())) {
            throw new IllegalArgumentException("发货审批通过后才可标记发货，当前审批状态：" + s.getApprovalStatus());
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
    public void confirmReceive(ShipmentConfirmReceiveReq req, String username, boolean isAdmin) {
        Long operatorId = currentUserId();
        // 证据留痕硬约束（决策#6）：内部销售代确认必须上传签收证据
        if (req.getProofFileIds() == null || req.getProofFileIds().isEmpty()) {
            throw new IllegalArgumentException("客户已收货必须上传签收证据（bizType=shipment_receive_proof）");
        }
        Shipment s = shipmentMapper.selectById(req.getShipmentId());
        if (s == null) {
            throw new IllegalArgumentException("发货单不存在：" + req.getShipmentId());
        }
        // 归属校验（2026-10-24 安全债修复）：写类仅申请人本人或 ROLE_ADMIN，不放开审批人
        if (!isAdmin && !Objects.equals(s.getApplicantId(), operatorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发货单不存在或无权操作：" + req.getShipmentId());
        }
        // 部分收货后可续收（2026-10-24 虎哥：玻璃水本次收 0，剩余量后续还要再收）
        // SHIPPED = 首次收货；PARTIAL_RECEIVED = 续收。FULL_RECEIVED/FINAL 为终态不可再收
        if (!"SHIPPED".equals(s.getShipmentStatus()) && !"PARTIAL_RECEIVED".equals(s.getShipmentStatus())) {
            throw new IllegalArgumentException("仅「已发货/部分收货」状态可标记客户收货，当前：" + s.getShipmentStatus());
        }
        List<ShipmentLine> lines = shipmentLineMapper.selectList(new LambdaQueryWrapper<ShipmentLine>()
                .eq(ShipmentLine::getShipmentId, s.getId()));
        Map<Long, ShipmentLine> lineByItem = lines.stream()
                .collect(Collectors.toMap(ShipmentLine::getItemId, l -> l));
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
        }
        // 全部收货判定必须覆盖发货单「全部」明细行（2026-10-24 虎哥实锤：玻璃水收 0 被误判全部收货）。
        // 前端 payload 只携带本次有收货动作的行（收 0 的行被过滤），逐行累加后必须回到全量行上判定，
        // 任一行 已收 < 已发 即为部分收货
        boolean allReceived = lines.stream().allMatch(l ->
                Objects.equals(l.getReceivedQty(), l.getShipmentQty()));
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
                // shipment_status 创建时即 PENDING（待发货），此处无需改；但回写审批状态为 APPROVED，
                // 供前端据此隐藏「编辑」入口（虎哥 2026-10-24：审批通过后不可再编辑，直接标记发货）
                s.setApprovalStatus("APPROVED");
                shipmentMapper.updateById(s);
                // 通知申请人（= 提交发货单的人）前往发货管理页自己发货（数据隔离：审批人未必能看该单）
                notifyApproved(s);
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
        // 退还本单占用的货物额度（2026-10-24 补齐）。
        // shippedQty 在 create 时即占用，任何让本单失效的路径（撤回 / 驳回 / 编辑减量）
        // 都必须退还，否则额度被永久占用，后续发货申请可用量莫名变少——
        // 此前撤回与编辑已退还，唯独驳回漏了（既有缺陷）。
        // 状态守卫：终态不再退，避免异常重试导致二次退还把额度退成负数方向。
        if (!"REJECTED".equals(s.getShipmentStatus()) && !"CANCELLED".equals(s.getShipmentStatus())) {
            releaseItemOccupancy(s.getId());
        }
        s.setShipmentStatus("REJECTED");
        s.setApprovalStatus("REJECTED");
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

    // ===================== 申请人撤回（决策#2 方案 B，2026-10-23 虎哥拍板） =====================

    @Override
    public void withdraw(Long shipmentId) {
        Shipment s = shipmentMapper.selectById(shipmentId);
        if (s == null) {
            throw new IllegalArgumentException("发货单不存在：" + shipmentId);
        }
        if (s.getProcessInstanceId() == null || s.getProcessInstanceId().isBlank()) {
            throw new IllegalArgumentException("该发货单没有进行中的审批流程，无需撤回");
        }
        // 引擎三道闸：发起人身份（admin 不代撤）+ 流程仍在运行；返回已审批人 userId
        List<Long> approvedUserIds = engine.withdraw(s.getProcessInstanceId(), currentUsername(),
                WorkflowEngineService.WITHDRAW_REASON_PREFIX + "发货申请，发货单 id=" + s.getId());
        // 退还货物额度：create 时已累加 item.shippedQty（占用），撤回必须退还，
        // 否则额度被永久占用（后续发货会误判超发）。
        releaseItemOccupancy(s.getId());
        // CANCELLED = 终态只读（方案 B）。发货状态字典 shipment_status 无 CANCELLED 项，
        // V56 一并补字典，避免列表状态列显示裸码（V55 同款教训）。
        s.setShipmentStatus("CANCELLED");
        s.setApprovalStatus("CANCELLED");
        s.setUpdatedBy(currentUserId());
        shipmentMapper.updateById(s);
        log.info("[shipment] 申请人撤回 id={} user={} 已审批人={}", shipmentId, currentUsername(), approvedUserIds);
        notifyWithdrawn(s, approvedUserIds);
    }

    /** 撤回通知（模板 SHIPMENT_WITHDRAWN，V56 种子）；无人审批过则不发 */
    private void notifyWithdrawn(Shipment s, List<Long> approvedUserIds) {
        if (approvedUserIds == null || approvedUserIds.isEmpty()) {
            log.debug("[notify] 撤回时尚无人审批，跳过通知 shipmentId={}", s.getId());
            return;
        }
        Project p = projectMapper.selectById(s.getProjectId());
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("SHIPMENT_WITHDRAWN");
        cmd.setReceivers(approvedUserIds);
        cmd.setBizType("SHIPMENT");
        cmd.setBizId(String.valueOf(s.getId()));
        cmd.setLinkUrl("/shipment");
        cmd.setDedupKey("SHIPMENT_WITHDRAWN:SHIPMENT:" + s.getId());
        cmd.setVars(Map.of(
                "batchNo", s.getBatchNo() == null ? "" : s.getBatchNo(),
                "projectName", p != null && p.getProjectName() != null ? p.getProjectName() : "",
                "operator", currentUsername() == null ? "" : currentUsername()));
        cmd.setOperatorId(currentUserId());
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

        // 项目名称模糊 → projectId 集合（两段式，与 LogServiceImpl「操作人模糊」同范式，
        // 不拼 SQL 无注入）。发货表只有 projectId 没有 projectName 列，只能先反查项目。
        // 修复既有缺陷：nameKw 此前算了没用，前端「项目名称」查询框实际无效。
        List<Long> projectIds = null;
        if (nameKw != null) {
            projectIds = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                            .like(Project::getProjectName, nameKw).select(Project::getId))
                    .stream().map(Project::getId).filter(Objects::nonNull).toList();
            if (projectIds.isEmpty()) {
                return new Page<>(current, size, 0);
            }
        }

        // 行级数据隔离（2026-10-24 虎哥）：与立项同口径 —— 非 ROLE_ADMIN 只看 applicantId
        // = 自己的发货单；ROLE_ADMIN 看全部。
        // ⚠️ 此前 viewAll 参数从 Controller 一路传进来却没被使用（全员可见），本次补上。
        // 发货人 shippedById 通常就是申请人自己（决策#2：发货人 = 操作人自身），
        // 故只按 applicantId 过滤即可覆盖「我要处理的单子」；确认收货同理。
        Long me = currentUserId();
        if (DataScopeHelper.denyAll(me)) {
            return new Page<>(current, size, 0);
        }
        IPage<Shipment> page = shipmentMapper.selectPage(new Page<>(current, size),
                DataScopeHelper.own(
                        new LambdaQueryWrapper<Shipment>()
                                .like(noKw != null, Shipment::getBatchNo, noKw)
                                .in(projectIds != null, Shipment::getProjectId, projectIds)
                                .eq(statusEq != null, Shipment::getShipmentStatus, statusEq)
                                .orderByDesc(Shipment::getCreatedAt),
                        Shipment::getApplicantId, me));
        return page.convert(s -> toVO(s, dictMaps(), userNamesOf(page.getRecords())));
    }

    @Override
    public ShipmentVO detail(Long shipmentId, String username, boolean isAdmin) {
        Shipment s = shipmentMapper.selectById(shipmentId);
        if (s == null) {
            throw new IllegalArgumentException("发货单不存在：" + shipmentId);
        }
        // 可见性三选一（2026-10-24 安全债修复）：申请人本人 / ROLE_ADMIN / 审批任务处理人（候选人）
        if (!isAdmin && !Objects.equals(s.getApplicantId(), currentUserId())
                && !engine.isCandidate(SHIPMENT_PROCESS_CODE, String.valueOf(shipmentId), username)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发货单不存在或无权访问：" + shipmentId);
        }
        return toVO(s, dictMaps(), userNamesOf(List.of(s)));
    }

    @Override
    public List<TimelineNodeVO> timeline(Long shipmentId) {
        // processKey 必传：businessKey 跨表相撞（发货单 #2 = 收款单 #2），不带类型过滤会串台
        return engine.listTimelineByBusinessKey(SHIPMENT_PROCESS_CODE, String.valueOf(shipmentId));
    }

    // ===================== 私有 =====================

    private ShipmentVO toVO(Shipment s, DictMaps dicts, Map<Long, String> userNames) {
        ShipmentVO vo = new ShipmentVO();
        vo.setId(s.getId());
        vo.setProjectId(s.getProjectId());
        vo.setBatchNo(s.getBatchNo());
        vo.setShipmentDate(s.getShipmentDate());
        vo.setShipmentAmount(s.getShipmentAmount());
        vo.setReturnedAmount(s.getReturnedAmount());
        vo.setShipmentStatus(s.getShipmentStatus());
        DictData st = dicts.shipmentStatus.get(s.getShipmentStatus());
        vo.setShipmentStatusName(st != null ? st.getDictLabel() : null);
        vo.setShipmentStatusStyle(st != null ? st.getStyle() : null);
        vo.setReceivableId(s.getReceivableId());
        vo.setProcessInstanceId(s.getProcessInstanceId());
        vo.setApprovalStatus(s.getApprovalStatus());
        DictData appr = dicts.shipmentApprovalStatus.get(s.getApprovalStatus());
        vo.setApprovalStatusName(appr != null ? appr.getDictLabel() : null);
        vo.setApprovalStatusStyle(appr != null ? appr.getStyle() : null);
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
            // V72：客户名按 customer_id 翻译（fin_project 已删该列）；与项目 N+1 同范式
            Customer c = p.getCustomerId() != null ? customerMapper.selectById(p.getCustomerId()) : null;
            vo.setCustomerName(c != null ? c.getCustomerName() : null);
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
                // V65：已收改回款口径——fin_shipment.received_amount 是废弃列（创建置 0 后无写方，
                // 项目级已于 ProjectServiceImpl 切实时聚合，此处补上：发货详情实时读关联应收单已回款
                vo.setReceivedAmount(r.getReceivedAmount() != null ? r.getReceivedAmount() : BigDecimal.ZERO);
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
        rv.setReceivedAmount(r.getReceivedAmount());
        rv.setUnreceivedAmount(r.getUnreceivedAmount());
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
                dictMap(DICT_RECEIVABLE_STATUS), dictMap(DICT_DOC_DIRECTION), dictMap(DICT_RECEIVABLE_SOURCE),
                dictMap(DICT_SHIPMENT_APPROVAL_STATUS));
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

    /**
     * 审批通过时通知申请人（= 提交发货单的销售经理）：引导其前往发货管理页自己发货。
     * 不通知审批人——审批人因数据隔离（非申请人看不到该单）不一定能点进 /shipment 操作，
     * 让申请人自行发货才是闭环（虎哥 2026-10-24）。链接已指向 /shipment，模板文案亦为申请人视角。
     */
    private void notifyApproved(Shipment s) {
        Long applicantId = s.getApplicantId();
        if (applicantId == null) {
            return;
        }
        Project p = projectMapper.selectById(s.getProjectId());
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("SHIPMENT_APPROVED_GUIDE");
        cmd.setReceivers(List.of(applicantId));
        cmd.setBizType("SHIPMENT");
        cmd.setBizId(String.valueOf(s.getId()));
        cmd.setLinkUrl("/shipment");
        cmd.setDedupKey("SHIPMENT_APPROVED_GUIDE:SHIPMENT:" + s.getId());
        cmd.setVars(Map.of(
                "batchNo", s.getBatchNo() == null ? "" : s.getBatchNo(),
                "projectName", p != null && p.getProjectName() != null ? p.getProjectName() : ""));
        cmd.setOperatorId(applicantId);
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
        final Map<String, DictData> shipmentApprovalStatus;

        DictMaps(Map<String, DictData> shipmentStatus, Map<String, DictData> shipmentLineStatus,
                 Map<String, DictData> receivableStatus, Map<String, DictData> docDirection,
                 Map<String, DictData> receivableSource, Map<String, DictData> shipmentApprovalStatus) {
            this.shipmentStatus = shipmentStatus;
            this.shipmentLineStatus = shipmentLineStatus;
            this.receivableStatus = receivableStatus;
            this.docDirection = docDirection;
            this.receivableSource = receivableSource;
            this.shipmentApprovalStatus = shipmentApprovalStatus;
        }
    }
}
