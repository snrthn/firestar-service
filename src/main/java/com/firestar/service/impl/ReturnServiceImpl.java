package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.common.FlowNodeUtil;
import com.firestar.common.Result;
import com.firestar.dto.NotifyCommand;
import com.firestar.dto.ReturnCreateReq;
import com.firestar.dto.ReturnLineItem;
import com.firestar.dto.ReturnLineVO;
import com.firestar.dto.ReturnVO;
import com.firestar.dto.ReturnWriteoffDetailVO;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.dto.TaskInfo;
import com.firestar.entity.DictData;
import com.firestar.entity.Invoice;
import com.firestar.entity.InvoiceAllocation;
import com.firestar.entity.Item;
import com.firestar.entity.ProcessDef;
import com.firestar.entity.Project;
import com.firestar.entity.Receipt;
import com.firestar.entity.Receivable;
import com.firestar.entity.ReturnLine;
import com.firestar.entity.ReturnOrder;
import com.firestar.entity.Role;
import com.firestar.entity.Shipment;
import com.firestar.entity.ShipmentLine;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.entity.WriteOff;
import com.firestar.mapper.DictDataMapper;
import com.firestar.mapper.InvoiceAllocationMapper;
import com.firestar.mapper.InvoiceMapper;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.ProcessDefMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceiptMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.ReturnLineMapper;
import com.firestar.mapper.ReturnOrderMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.ShipmentLineMapper;
import com.firestar.mapper.ShipmentMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.mapper.WriteOffMapper;
import com.firestar.service.NotificationService;
import com.firestar.service.ReturnService;
import com.firestar.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 退货服务实现（V67 阶段二）。
 *
 * <h3>三冲减链（三级审批末节点通过后自动执行，单事务）</h3>
 * <ol>
 *   <li><b>红字应收单</b>（恒执行）：doc_direction=RED + original_receivable_id + return_id +
 *       source_type=FROM_RETURN；金额正数存储，项目聚合 RED 取负（V65 口径）；</li>
 *   <li><b>红字发票</b>（条件：原应收单已开票 ≥ 退货金额）：docDirection=RED +
 *       originalInvoiceId + allocation 核销到<b>原应收单</b>（同构 V65 红冲入口，
 *       滚 origin.invoiced -= use / uninvoiced += use）；</li>
 *   <li><b>红字收款单=退款单</b>（条件：原应收单已回款 ≥ 退货金额）：docDirection=RED +
 *       receiptType=REFUND + originalReceiptId + return_id；核销行 WO- 即<b>反向核销记录</b>
 *       （RED 方向核销原应收单，originalWriteOffId 留痕指向被冲的原行，
 *       滚 origin.received -= use / unreceived += use）。</li>
 * </ol>
 *
 * <p>数字范式：原单一动不动（审计），全部冲减走红字单；核销行增量语义天然支持
 * 部分退货（退 350 中的 200：红字单金额 200，原行 350 保持 NORMAL）。</p>
 *
 * <p>审批中状态推进（字典 approval_status）：提交 → 审批中 APPROVING（三级节点不再各立状态，
 * 层级由 VO.currentNodeName 取自 Flowable 当前节点名表达）→ 审批通过 APPROVED；
 * 驳回 REJECTED；撤销 CANCELLED。冲减执行进度走 executeStatus（return_execute_status）。</p>
 */
@Slf4j
@Service
public class ReturnServiceImpl implements ReturnService {

    /** 退货审批流程定义 key（sys_process_def.process_code；审批中心 enrich 分发用，需 public） */
    public static final String RETURN_PROCESS_CODE = "return_approval";

    /** 三级审批角色（node_list 与字典中间态一一对应） */
    private static final String ROLE_SUPERVISOR = "ROLE_SUPERVISOR";
    private static final String ROLE_FINANCE = "ROLE_FINANCE";
    private static final String ROLE_SALES_MANAGER = "ROLE_SALES_MANAGER";

    /**
     * 可撤销的审批中间态（字典 approval_status 的 APPROVING 推进态）。
     * EXECUTING（执行中/已冲减）之后流程已终结，不可撤——与 cancel 的三道闸一致。
     */
    private static final Set<String> CANCELLABLE_STATES =
            Set.of("APPROVING");

    /** 退货单号前缀：TH-yyyyMMdd-NNN（与 SK-/FP-/YS-/PJ-/FH- 同范式） */
    private static final String RETURN_NO_PREFIX = "TH";
    private static final DateTimeFormatter RETURN_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String DICT_RETURN_APPROVAL = "approval_status";
    private static final String DICT_RETURN_EXECUTE = "return_execute_status";

    private final ReturnOrderMapper returnOrderMapper;
    private final ReturnLineMapper returnLineMapper;
    private final ShipmentMapper shipmentMapper;
    private final ShipmentLineMapper shipmentLineMapper;
    private final ItemMapper itemMapper;
    private final ProjectMapper projectMapper;
    private final ReceivableMapper receivableMapper;
    private final ReceiptMapper receiptMapper;
    private final WriteOffMapper writeOffMapper;
    private final InvoiceMapper invoiceMapper;
    private final InvoiceAllocationMapper invoiceAllocationMapper;
    private final ProcessDefMapper processDefMapper;
    private final DictDataMapper dictDataMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final WorkflowEngineService engine;
    private final NotificationService notificationService;

    @Autowired
    public ReturnServiceImpl(ReturnOrderMapper returnOrderMapper, ReturnLineMapper returnLineMapper,
                             ShipmentMapper shipmentMapper, ShipmentLineMapper shipmentLineMapper,
                             ItemMapper itemMapper, ProjectMapper projectMapper,
                             ReceivableMapper receivableMapper, ReceiptMapper receiptMapper,
                             WriteOffMapper writeOffMapper, InvoiceMapper invoiceMapper,
                             InvoiceAllocationMapper invoiceAllocationMapper,
                             ProcessDefMapper processDefMapper, DictDataMapper dictDataMapper,
                             UserMapper userMapper, RoleMapper roleMapper, UserRoleMapper userRoleMapper,
                             WorkflowEngineService engine, NotificationService notificationService) {
        this.returnOrderMapper = returnOrderMapper;
        this.returnLineMapper = returnLineMapper;
        this.shipmentMapper = shipmentMapper;
        this.shipmentLineMapper = shipmentLineMapper;
        this.itemMapper = itemMapper;
        this.projectMapper = projectMapper;
        this.receivableMapper = receivableMapper;
        this.receiptMapper = receiptMapper;
        this.writeOffMapper = writeOffMapper;
        this.invoiceMapper = invoiceMapper;
        this.invoiceAllocationMapper = invoiceAllocationMapper;
        this.processDefMapper = processDefMapper;
        this.dictDataMapper = dictDataMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.engine = engine;
        this.notificationService = notificationService;
    }

    // ===================== 发起退货（创建即三级审批） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long shipmentId, ReturnCreateReq req) {
        Long operatorId = currentUserId();
        if (req.getLines() == null || req.getLines().isEmpty()) {
            throw new IllegalArgumentException("退货明细不能为空");
        }
        Shipment shipment = shipmentMapper.selectById(shipmentId);
        if (shipment == null) {
            throw new IllegalArgumentException("发货单不存在：" + shipmentId);
        }
        // 仅审批通过的发货单可退（退货发生在发货确认之后；审批中的发货单货都没发，无退货对象）
        if (!"APPROVED".equals(shipment.getApprovalStatus())) {
            throw new IllegalArgumentException("仅审批通过的发货单可发起退货，当前：" + shipment.getApprovalStatus());
        }
        // 流程定义必须存在且启用
        ProcessDef def = processDefMapper.selectOne(new LambdaQueryWrapper<ProcessDef>()
                .eq(ProcessDef::getProcessCode, RETURN_PROCESS_CODE)
                .eq(ProcessDef::getStatus, "ENABLED"));
        if (def == null) {
            throw new IllegalArgumentException("退货审批流程未配置或已停用：" + RETURN_PROCESS_CODE);
        }

        // 逐行校验 + 汇总金额（行单价取发货行当时单价，不接受传入）
        BigDecimal amount = BigDecimal.ZERO;
        List<ReturnLine> lines = new ArrayList<>();
        for (ReturnLineItem item : req.getLines()) {
            if (item.getShipmentLineId() == null) {
                throw new IllegalArgumentException("明细行缺少发货明细 ID");
            }
            if (item.getReturnQty() == null || item.getReturnQty() <= 0) {
                throw new IllegalArgumentException("退货数量必须为正");
            }
            ShipmentLine sl = shipmentLineMapper.selectById(item.getShipmentLineId());
            if (sl == null) {
                throw new IllegalArgumentException("发货明细行不存在：" + item.getShipmentLineId());
            }
            if (!Objects.equals(sl.getShipmentId(), shipment.getId())) {
                throw new IllegalArgumentException("发货明细行不属于该发货单：" + item.getShipmentLineId());
            }
            int received = sl.getReceivedQty() == null ? 0 : sl.getReceivedQty();
            int returned = sl.getReturnedQty() == null ? 0 : sl.getReturnedQty();
            if (item.getReturnQty() > received - returned) {
                Item it = itemMapper.selectById(sl.getItemId());
                throw new IllegalArgumentException(String.format(
                        "货物「%s」退货超量：已收 %d，已退 %d，可退 %d，本次申请 %d",
                        it != null ? it.getItemName() : sl.getItemId(),
                        received, returned, received - returned, item.getReturnQty()));
            }
            BigDecimal unitPrice = sl.getUnitPrice() == null ? BigDecimal.ZERO : sl.getUnitPrice();
            BigDecimal lineAmt = unitPrice.multiply(BigDecimal.valueOf(item.getReturnQty()));
            ReturnLine rl = new ReturnLine();
            rl.setShipmentLineId(sl.getId());
            rl.setItemId(sl.getItemId());
            rl.setReturnQty(item.getReturnQty());
            rl.setUnitPrice(unitPrice);
            rl.setLineAmount(lineAmt);
            lines.add(rl);
            amount = amount.add(lineAmt);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("退货金额必须大于 0（行单价 × 数量汇总）");
        }

        ReturnOrder ro = new ReturnOrder();
        ro.setReturnNo(generateReturnNo());
        ro.setProjectId(shipment.getProjectId());
        ro.setShipmentId(shipment.getId());
        ro.setReturnAmount(amount);
        ro.setReturnReason(req.getReturnReason());
        ro.setApplicantId(operatorId);
        ro.setAppliedAt(LocalDateTime.now());
        ro.setCreatedBy(operatorId);
        ro.setExecuteStatus("NOT_EXECUTED");
        // 先插单拿自增 id：Flowable businessKey 在 start 时写死，必须 = 退货单 ID
        // （时间线/候选查询都按 businessKey 收敛，占位值会全盘错位）
        returnOrderMapper.insert(ro);
        for (ReturnLine rl : lines) {
            rl.setReturnId(ro.getId());
            returnLineMapper.insert(rl);
        }
        // 再拉三级审批（businessKey = 退货单 id）
        Map<String, Object> vars = new HashMap<>();
        vars.put("applicantUsername", currentUsername());
        vars.put("applicantId", operatorId);
        String instanceId = engine.start(RETURN_PROCESS_CODE, String.valueOf(ro.getId()), vars);
        ro.setProcessInstanceId(instanceId);
        // 第一节点（主管）已激活 → 上级审批中
        ro.setApprovalStatus("APPROVING");
        returnOrderMapper.updateById(ro);
        log.info("[return] 创建并发起三级审批 id={} no={} shipmentId={} amount={}",
                ro.getId(), ro.getReturnNo(), shipment.getId(), amount);
        notifySubmitted(ro);
        return ro.getId();
    }

    // ===================== 撤销（仅审批中） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        ReturnOrder ro = returnOrderMapper.selectById(id);
        if (ro == null) {
            throw new IllegalArgumentException("退货单不存在：" + id);
        }
        if (ro.getProcessInstanceId() == null || ro.getProcessInstanceId().isBlank()) {
            throw new IllegalArgumentException("该退货单没有进行中的审批流程，无需撤销");
        }
        List<Long> approvedUserIds = engine.withdraw(ro.getProcessInstanceId(), currentUsername(),
                WorkflowEngineService.WITHDRAW_REASON_PREFIX + "退货申请，退货单 id=" + ro.getId());
        ro.setApprovalStatus("CANCELLED");
        ro.setUpdatedBy(currentUserId());
        returnOrderMapper.updateById(ro);
        log.info("[return] 申请人撤销 id={} user={} 已审批人={}", id, currentUsername(), approvedUserIds);
        notifyWithdrawn(ro, approvedUserIds);
    }

    // ===================== 审批通过（三级推进 + 末节点三冲减） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeTask(String taskId, Map<String, Object> variables) {
        String comment = variables == null ? null : Objects.toString(variables.get("comment"), null);
        // 时序双保险：complete 前先拿 businessKey（末节点通过后实例即终态，runtime 被清理）
        String bizKeyUpfront = engine.businessKeyOfTask(taskId);
        String instanceId = engine.complete(taskId, variables, comment);
        List<TaskInfo> next = engine.listTasks(instanceId);
        String bizKey = bizKeyUpfront != null ? bizKeyUpfront : engine.getBusinessKey(instanceId);
        ReturnOrder ro = (bizKey != null) ? returnOrderMapper.selectById(Long.parseLong(bizKey)) : null;
        if (ro == null) {
            log.warn("[return] 审批通过但未回写：bizKey={} 找不到退货单 instanceId={}", bizKey, instanceId);
            return;
        }
        if (next.isEmpty()) {
            // 末节点（经理审批）通过 → 执行三冲减链
            ro.setApprovalStatus("APPROVED");
            ro.setExecuteStatus("EXECUTING");
            ro.setUpdatedBy(currentUserId());
            returnOrderMapper.updateById(ro);
            executeReturn(ro);
            log.info("[return] 三级审批通过并执行冲减 id={} amount={}", ro.getId(), ro.getReturnAmount());
            notifyApproved(ro);
        } else {
            // 中间节点：按下一任务节点 key 推进审批状态 + 通知下一级审批人
            String nodeId = next.get(0).getNodeId();
            String status;
            String notifyRole;
            if ("node_2".equals(nodeId)) {
                status = "APPROVING";
                notifyRole = ROLE_FINANCE;
            } else if ("node_3".equals(nodeId)) {
                status = "APPROVING";
                notifyRole = ROLE_SALES_MANAGER;
            } else {
                log.warn("[return] 未知下一节点 nodeId={} instanceId={}", nodeId, instanceId);
                return;
            }
            ro.setApprovalStatus(status);
            ro.setUpdatedBy(currentUserId());
            returnOrderMapper.updateById(ro);
            notifyNextApprover(ro, notifyRole);
            log.info("[return] 审批推进 id={} status={} nextNode={}", ro.getId(), status, nodeId);
        }
    }

    // ===================== 审批驳回 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectTask(String taskId, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("驳回理由不能为空");
        }
        engine.assertTaskHandleable(taskId);
        String bizKey = engine.businessKeyOfTask(taskId);
        if (bizKey == null) {
            log.warn("[return] 驳回时取不到业务键（任务可能已结束）taskId={}", taskId);
            return;
        }
        ReturnOrder ro = returnOrderMapper.selectById(Long.parseLong(bizKey));
        if (ro == null) {
            log.warn("[return] 驳回时找不到退货单 bizKey={} taskId={}", bizKey, taskId);
            return;
        }
        // 三级流程：直接终结实例（逐级退回目标节点留 V2；rejectable 标志已种，行为统一 terminate）
        if (ro.getProcessInstanceId() != null) {
            engine.terminate(ro.getProcessInstanceId(), "退货申请被退回：" + comment);
        }
        ro.setApprovalStatus("REJECTED");
        ro.setUpdatedBy(currentUserId());
        returnOrderMapper.updateById(ro);
        log.info("[return] 审批驳回回写 id={} comment={}", ro.getId(), comment);
        notifyRejected(ro, currentUsername(), comment);
    }

    // ===================== 三冲减链（executeReturn） =====================

    /**
     * 三级审批末节点通过后自动执行三冲减链（与 completeTask 同事务，任一步失败整体回滚）。
     *
     * <p>原应收单 = 发货单关联的 FROM_SHIPMENT 应收单（客户收货时生成）。</p>
     */
    private void executeReturn(ReturnOrder ro) {
        Receivable origin = receivableMapper.selectOne(new LambdaQueryWrapper<Receivable>()
                .eq(Receivable::getShipmentId, ro.getShipmentId()));
        if (origin == null) {
            // 发货单未确认收货 → 无应收单可冲（理论不可达：退货仅 APPROVED 发货单可发起，
            // 而收货在发货审批之后；防御性处理：只置完成态，不建任何红字单）
            log.warn("[return] 冲减跳过：发货单无关联应收单 shipmentId={}", ro.getShipmentId());
            ro.setApprovalStatus("APPROVED");
            ro.setExecuteStatus("COMPLETED");
            ro.setUpdatedBy(currentUserId());
            returnOrderMapper.updateById(ro);
            return;
        }
        BigDecimal amount = ro.getReturnAmount();
        Long operatorId = currentUserId();
        List<String> notes = new ArrayList<>();

        // ① 红字应收单（恒执行）：负应收凭证，金额正数存储 + RED 方向
        Receivable red = new Receivable();
        red.setProjectId(ro.getProjectId());
        // 关键：红字应收单绝不能带 shipment_id——fin_receivable.uk_receivable_shipment
        // 约束「一个发货单只能有一条应收单」，红字单若挂同发货单会撞 Duplicate entry。
        // 语义上本单归属退货（return_id），与发货侧的蓝字应收单正交，故 shipment_id 留 NULL
        // （唯一索引允许多值 NULL，不影响已存在的发货生成单）。
        red.setShipmentId(null);
        red.setDocDirection("RED");
        red.setOriginalReceivableId(origin.getId());
        red.setReturnId(ro.getId());
        red.setSourceType("FROM_RETURN");
        red.setReceivableAmount(amount);
        red.setWrittenOffAmount(BigDecimal.ZERO);
        red.setUnwrittenOffAmount(amount);
        red.setInvoicedAmount(BigDecimal.ZERO);
        red.setUninvoicedAmount(amount);
        red.setReceivedAmount(BigDecimal.ZERO);
        red.setUnreceivedAmount(amount);
        red.setReceivableDate(LocalDate.now());
        red.setStatus("CONFIRMED");
        red.setReceivableNo(generateReceivableNo());
        red.setCreatedBy(operatorId);
        receivableMapper.insert(red);
        addToProjectReceivable(ro.getProjectId(), amount.negate());
        notes.add("红字应收单 " + red.getReceivableNo() + " ¥" + amount);

        // ② 红字发票（条件：原应收单已开票 ≥ 可冲金额；按蓝字票创建顺序冲，可多张拼）
        BigDecimal invoiced = nz(origin.getInvoicedAmount());
        BigDecimal invRemain = amount.min(invoiced);
        if (invRemain.compareTo(BigDecimal.ZERO) > 0) {
            List<Invoice> blueInvoices = invoiceMapper.selectList(new LambdaQueryWrapper<Invoice>()
                    .eq(Invoice::getReceivableId, origin.getId())
                    .eq(Invoice::getDocDirection, "BLUE")
                    .eq(Invoice::getApprovalStatus, "APPROVED")
                    .orderByAsc(Invoice::getId));
            for (Invoice blue : blueInvoices) {
                if (invRemain.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                BigDecimal use = invRemain.min(nz(blue.getInvoiceAmount()));
                if (use.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                Invoice redIv = new Invoice();
                redIv.setProjectId(ro.getProjectId());
                redIv.setReceivableId(origin.getId());   // 核销目标 = 原应收单（V65 红冲范式）
                redIv.setInvoiceAmount(use);
                redIv.setDocDirection("RED");
                redIv.setOriginalInvoiceId(blue.getId());
                redIv.setReturnId(ro.getId());
                redIv.setInvoiceType(blue.getInvoiceType());
                redIv.setApprovalStatus("APPROVED");        // 执行凭证：免再审，直接生效
                redIv.setInvoiceDate(LocalDate.now());
                redIv.setCreatedBy(operatorId);
                redIv.setInvoiceNo(generateInvoiceNo());
                invoiceMapper.insert(redIv);
                // allocation 核销行（NORMAL：有效的反向核销记录）
                InvoiceAllocation ia = new InvoiceAllocation();
                ia.setAllocationNo(generateAllocationNo());
                ia.setInvoiceId(redIv.getId());
                ia.setReceivableId(origin.getId());
                ia.setAmount(use);
                ia.setStatus("NORMAL");
                ia.setCreatedBy(operatorId);
                invoiceAllocationMapper.insert(ia);
                // 滚原应收单：已开票 -= use / 未开票 += use（同构 V65 applyInvoiceAllocations RED 分支）
                origin.setInvoicedAmount(invoiced.subtract(use));
                origin.setUninvoicedAmount(nz(origin.getUninvoicedAmount()).add(use));
                receivableMapper.updateById(origin);
                invoiced = origin.getInvoicedAmount();
                invRemain = invRemain.subtract(use);
                accumulateProjectInvoiced(ro.getProjectId(), use.negate(), "RED");
                notes.add("红字发票 " + redIv.getInvoiceNo() + " ¥" + use + "（原票 " + blue.getInvoiceNo() + "）");
            }
        } else {
            notes.add("未开票，跳过红字发票");
        }

        // ③ 红字收款单=退款单（条件：原应收单已回款 ≥ 可退金额）
        BigDecimal received = nz(origin.getReceivedAmount());
        BigDecimal recRemain = amount.min(received);
        if (recRemain.compareTo(BigDecimal.ZERO) > 0) {
            // 被冲的原核销行（NORMAL，按金额降序累计覆盖 recRemain；链式留痕取首个）
            List<WriteOff> normalRows = writeOffMapper.selectList(new LambdaQueryWrapper<WriteOff>()
                    .eq(WriteOff::getReceivableId, origin.getId())
                    .eq(WriteOff::getStatus, "NORMAL")
                    .orderByDesc(WriteOff::getAmount));
            Long originalWriteOffId = null;
            Long originalReceiptId = null;
            BigDecimal cover = BigDecimal.ZERO;
            for (WriteOff w : normalRows) {
                cover = cover.add(nz(w.getAmount()));
                if (originalWriteOffId == null) {
                    originalWriteOffId = w.getId();
                    originalReceiptId = w.getReceiptId();
                }
                if (cover.compareTo(recRemain) >= 0) {
                    break;
                }
            }
            Receipt redRc = new Receipt();
            redRc.setProjectId(ro.getProjectId());
            redRc.setReceiptAmount(recRemain);
            redRc.setDocDirection("RED");
            redRc.setReceiptType("REFUND");              // 虎哥决策#2：与普通红冲单区分
            redRc.setOriginalReceiptId(originalReceiptId);
            redRc.setReturnId(ro.getId());
            redRc.setReceiptDate(LocalDate.now());
            redRc.setReceiptStatus("RECEIVED");
            redRc.setApprovalStatus("APPROVED");         // 执行凭证：三级审批已过，免再审
            redRc.setReceiptNo(generateReceiptNo());
            redRc.setCreatedBy(operatorId);
            receiptMapper.insert(redRc);
            // 反向核销行（WO-，NORMAL，RED 方向；originalWriteOffId 链式留痕）
            WriteOff wo = new WriteOff();
            wo.setWriteOffNo(generateWriteOffNo());
            wo.setReceiptId(redRc.getId());
            wo.setReceivableId(origin.getId());
            wo.setAmount(recRemain);
            wo.setStatus("NORMAL");
            wo.setWriteOffMethod("AUTO");
            wo.setOriginalWriteOffId(originalWriteOffId);
            wo.setWriteOffDate(LocalDate.now());
            wo.setCreatedBy(operatorId);
            writeOffMapper.insert(wo);
            // 滚原应收单：已回款 -= recRemain / 未回款 += recRemain（同构 V65 applyWriteOffs RED 分支）
            origin.setReceivedAmount(received.subtract(recRemain));
            origin.setUnreceivedAmount(nz(origin.getUnreceivedAmount()).add(recRemain));
            receivableMapper.updateById(origin);
            // 滚退款单自身核销列（同构 applyWriteOffs：written = Σ 行金额）
            redRc.setWrittenOffAmount(recRemain);
            redRc.setUnwrittenOffAmount(redRc.getReceiptAmount().subtract(recRemain));
            receiptMapper.updateById(redRc);
            accumulateProjectCollected(ro.getProjectId(), recRemain.negate(), "RED");
            notes.add("退款单 " + redRc.getReceiptNo() + " ¥" + recRemain
                    + "（反向核销 " + wo.getWriteOffNo() + "）");
        } else {
            notes.add("未回款，跳过退款单");
        }

        // ④ 回写发货明细行的已退数量（按行累加 returned_qty）
        //    不回写的后果：下次可退余额 receivedQty - returnedQty 永不减少 →
        //    同一批货可被反复发起退货，超退没有任何闸门能拦（后端 create 的余量校验形同虚设）。
        List<ReturnLine> rls = returnLineMapper.selectList(new LambdaQueryWrapper<ReturnLine>()
                .eq(ReturnLine::getReturnId, ro.getId()));
        Map<Long, Integer> qtyByShipmentLine = new HashMap<>();
        for (ReturnLine rl : rls) {
            if (rl.getShipmentLineId() == null) {
                continue;
            }
            qtyByShipmentLine.merge(rl.getShipmentLineId(),
                    rl.getReturnQty() == null ? 0 : rl.getReturnQty(), Integer::sum);
        }
        List<String> rollbackNotes = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : qtyByShipmentLine.entrySet()) {
            ShipmentLine sl = shipmentLineMapper.selectById(e.getKey());
            if (sl == null) {
                continue;
            }
            int returned = sl.getReturnedQty() == null ? 0 : sl.getReturnedQty();
            sl.setReturnedQty(returned + e.getValue());
            shipmentLineMapper.updateById(sl);
            Item it = itemMapper.selectById(sl.getItemId());
            rollbackNotes.add((it == null ? "货物#" + sl.getItemId() : it.getItemName())
                    + " 已退累计 " + returned + " → " + sl.getReturnedQty());
        }
        if (!rollbackNotes.isEmpty()) {
            notes.add("发货明细已退量回写：" + String.join("；", rollbackNotes));
        }

        // 冲减完成：状态推进 + ext 留执行说明（详情页展示）
        ro.setApprovalStatus("APPROVED");   // V75：审批态用通用 approval_status，无 COMPLETED；完成度由 executeStatus 表达
        ro.setExecuteStatus("COMPLETED");
        ro.setUpdatedBy(operatorId);
        Map<String, Object> ext = ro.getExt();
        if (ext == null) {
            ext = new HashMap<>();
        }
        ext.put("returnExecuteNotes", notes);
        ext.put("returnExecutedAt", LocalDateTime.now().toString());
        ro.setExt(ext);
        returnOrderMapper.updateById(ro);
    }

    // ===================== 查询 =====================

    @Override
    public IPage<ReturnVO> page(String username, boolean viewAll, Integer pageNum, Integer pageSize,
                                String projectNo, String projectName, String status) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? 10 : pageSize;
        LambdaQueryWrapper<ReturnOrder> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(ReturnOrder::getApprovalStatus, status);
        }
        if (projectNo != null && !projectNo.isBlank() || projectName != null && !projectName.isBlank()) {
            Set<Long> pids = projectMapper.selectList(new LambdaQueryWrapper<Project>() {{
                        if (projectNo != null && !projectNo.isBlank()) {
                            like(Project::getProjectNo, projectNo);
                        }
                        if (projectName != null && !projectName.isBlank()) {
                            like(Project::getProjectName, projectName);
                        }
                    }}).stream().map(Project::getId).collect(Collectors.toSet());
            if (pids.isEmpty()) {
                return new Page<>(current, size, 0);
            }
            qw.in(ReturnOrder::getProjectId, pids);
        }
        qw.orderByDesc(ReturnOrder::getId);
        IPage<ReturnOrder> p = returnOrderMapper.selectPage(new Page<>(current, size), qw);
        return toVOPage(p, username);
    }

    @Override
    public ReturnVO detail(Long id, String username, boolean isAdmin) {
        ReturnOrder ro = returnOrderMapper.selectById(id);
        if (ro == null) {
            throw new IllegalArgumentException("退货单不存在：" + id);
        }
        // 归属三选一：申请人本人 / 数据隔离放行（viewAll）/ 审批流程处理人
        boolean mine = Objects.equals(ro.getApplicantId(), currentUserId());
        if (!mine && !isAdmin && !engine.isCandidate(RETURN_PROCESS_CODE, String.valueOf(ro.getId()), username)) {
            throw new IllegalArgumentException("退货单不存在或无权操作：" + id);
        }
        ReturnVO vo = toVO(ro, myPendingTaskMap(username), currentUserId());
        fillLabels(vo, ro);
        vo.setLines(lineVOs(ro.getId()));
        vo.setTimeline(engine.listTimelineByBusinessKey(RETURN_PROCESS_CODE, String.valueOf(ro.getId())));
        if ("COMPLETED".equals(ro.getExecuteStatus())) {
            vo.setWriteoffDetail(buildWriteoffDetail(ro));
        }
        return vo;
    }

    @Override
    public IPage<ReturnVO> pending(String username, Integer pageNum, Integer pageSize) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? 10 : pageSize;
        // 我的待办任务（跨流程）→ 收敛到 return_approval 的 businessKey 集合
        Map<String, String> myTaskByBizKey = engine.listMyTasks(username).stream()
                .filter(t -> RETURN_PROCESS_CODE.equals(t.getProcessDefinitionKey()))
                .collect(Collectors.toMap(TaskInfo::getBizKey, TaskInfo::getTaskId, (a, b) -> a));
        if (myTaskByBizKey.isEmpty()) {
            return new Page<>(current, size, 0);
        }
        List<Long> ids = myTaskByBizKey.keySet().stream()
                .map(s -> {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return new Page<>(current, size, 0);
        }
        IPage<ReturnOrder> p = returnOrderMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<ReturnOrder>()
                        .in(ReturnOrder::getId, ids)
                        .orderByDesc(ReturnOrder::getId));
        IPage<ReturnVO> vp = toVOPage(p, username);
        for (ReturnVO vo : vp.getRecords()) {
            vo.setMyPendingTaskId(myTaskByBizKey.get(String.valueOf(vo.getId())));
        }
        return vp;
    }

    @Override
    public ReturnWriteoffDetailVO writeoffDetail(Long id) {
        ReturnOrder ro = returnOrderMapper.selectById(id);
        if (ro == null) {
            throw new IllegalArgumentException("退货单不存在：" + id);
        }
        if (!"COMPLETED".equals(ro.getExecuteStatus())) {
            throw new IllegalArgumentException("该退货单尚未执行冲减，无冲减明细");
        }
        return buildWriteoffDetail(ro);
    }

    @Override
    public List<TimelineNodeVO> timeline(Long id) {
        return engine.listTimelineByBusinessKey(RETURN_PROCESS_CODE, String.valueOf(id));
    }

    // ===================== 内部：VO 装配 =====================

    private IPage<ReturnVO> toVOPage(IPage<ReturnOrder> p, String username) {
        Map<String, String> taskByBizKey = myPendingTaskMap(username);
        Map<String, DictData> approvalDict = dictMap(DICT_RETURN_APPROVAL);
        Map<String, DictData> executeDict = dictMap(DICT_RETURN_EXECUTE);
        // 当前用户 ID 循环外算一次（每单都查库会 N+1）
        Long operatorId = currentUserId();
        List<ReturnVO> vos = new ArrayList<>(p.getRecords().size());
        for (ReturnOrder ro : p.getRecords()) {
            vos.add(toVO(ro, taskByBizKey, operatorId));
        }
        // 空结果集直接返回：下游按 records 的 projectId/applicantId 做的批量 IN 查询
        // 在空集上会生成 `WHERE id IN ()`，MySQL 视为语法错误（线上零退货单时必炸）。
        // 没有记录也就无需翻译，早退即可。
        if (p.getRecords().isEmpty()) {
            IPage<ReturnVO> vp = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
            vp.setRecords(vos);
            return vp;
        }
        // 字典与项目翻译在 toVO 内逐个查会 N+1，这里批量预热后逐单装配
        Map<Long, Project> projects = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .in(Project::getId, p.getRecords().stream().map(ReturnOrder::getProjectId)
                                .collect(Collectors.toSet())))
                .stream().collect(Collectors.toMap(Project::getId, x -> x));
        Map<Long, String> applicantNames = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getId, p.getRecords().stream().map(ReturnOrder::getApplicantId)
                                .filter(Objects::nonNull).collect(Collectors.toSet()))
                        .select(User::getId, User::getNickname, User::getUsername))
                .stream().collect(Collectors.toMap(User::getId,
                        u -> u.getNickname() != null && !u.getNickname().isBlank()
                                ? u.getNickname() : u.getUsername(),
                        (a, b) -> a));
        for (ReturnVO vo : vos) {
            ReturnOrder ro = p.getRecords().stream()
                    .filter(x -> x.getId().equals(vo.getId())).findFirst().orElse(null);
            if (ro == null) {
                continue;
            }
            Project pr = projects.get(ro.getProjectId());
            if (pr != null) {
                vo.setProjectNo(pr.getProjectNo());
                vo.setProjectName(pr.getProjectName());
            }
            vo.setApplicantName(applicantNames.get(ro.getApplicantId()));
            vo.setApprovalStatusName(labelOf(approvalDict, ro.getApprovalStatus()));
            vo.setApprovalStatusStyle(styleOf(approvalDict, ro.getApprovalStatus()));
            vo.setExecuteStatusName(labelOf(executeDict, ro.getExecuteStatus()));
            vo.setExecuteStatusStyle(styleOf(executeDict, ro.getExecuteStatus()));
            // 审批中回填当前节点名（Flowable 当前活跃任务名），供列表「卡在第几级」展示
            if ("APPROVING".equals(ro.getApprovalStatus()) && ro.getProcessInstanceId() != null) {
                List<TaskInfo> tasks = engine.listTasks(ro.getProcessInstanceId());
                if (tasks != null && !tasks.isEmpty()) {
                    // V75：只有多节点流程才回填「当前节点名」（本流程三级审批，会回填）
                    if (FlowNodeUtil.isMultiNode(processDefMapper, RETURN_PROCESS_CODE)) {
                        vo.setCurrentNodeName(tasks.get(0).getTaskName());
                    }
                }
            }
        }
        IPage<ReturnVO> vp = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        vp.setRecords(vos);
        return vp;
    }

    private ReturnVO toVO(ReturnOrder ro, Map<String, String> taskByBizKey, Long operatorId) {
        ReturnVO vo = new ReturnVO();
        vo.setId(ro.getId());
        vo.setReturnNo(ro.getReturnNo());
        vo.setProjectId(ro.getProjectId());
        vo.setShipmentId(ro.getShipmentId());
        vo.setReturnAmount(ro.getReturnAmount());
        vo.setReturnReason(ro.getReturnReason());
        vo.setApprovalStatus(ro.getApprovalStatus());
        vo.setExecuteStatus(ro.getExecuteStatus());
        vo.setProcessInstanceId(ro.getProcessInstanceId());
        vo.setApplicantId(ro.getApplicantId());
        vo.setAppliedAt(ro.getAppliedAt());
        vo.setCreatedAt(ro.getCreatedAt());
        vo.setMyPendingTaskId(taskByBizKey.get(String.valueOf(ro.getId())));
        // 可撤销 = 仍在审批中 ∧ 本人申请（后端 cancel 的三道闸仍是唯一权威，此字段仅控前端按钮显隐）。
        // V75：三级节点态已统一为 APPROVING（层级看 VO.currentNodeName），执行链进度走 executeStatus。
        vo.setCanCancel(Boolean.TRUE.equals(
                CANCELLABLE_STATES.contains(ro.getApprovalStatus())
                        && operatorId != null && operatorId.equals(ro.getApplicantId())));
        Shipment s = shipmentMapper.selectById(ro.getShipmentId());
        if (s != null) {
            vo.setShipmentBatchNo(s.getBatchNo());
        }
        return vo;
    }

    /**
     * 单条 VO 的字典与关联翻译（detail 路径专用；列表走 toVOPage 的批量装配，不重复查库）。
     *
     * <p>为什么分开：detail 一次只装一条，逐个查库只有常数次；列表几十条走同一条路径会 N+1，
     * 因此 toVOPage 另写批量版。二者翻译口径必须一致，否则同一单在列表和详情会显示不同名字。</p>
     */
    private void fillLabels(ReturnVO vo, ReturnOrder ro) {
        Project pr = ro.getProjectId() == null ? null : projectMapper.selectById(ro.getProjectId());
        if (pr != null) {
            vo.setProjectNo(pr.getProjectNo());
            vo.setProjectName(pr.getProjectName());
        }
        if (ro.getApplicantId() != null) {
            User u = userMapper.selectById(ro.getApplicantId());
            if (u != null) {
                // 全站口径（同 ShipmentServiceImpl.userNamesOf）：昵称优先，空则回退用户名
                vo.setApplicantName(u.getNickname() != null && !u.getNickname().isBlank()
                        ? u.getNickname() : u.getUsername());
            }
        }
        Map<String, DictData> approvalDict = dictMap(DICT_RETURN_APPROVAL);
        vo.setApprovalStatusName(labelOf(approvalDict, ro.getApprovalStatus()));
        vo.setApprovalStatusStyle(styleOf(approvalDict, ro.getApprovalStatus()));
        Map<String, DictData> executeDict = dictMap(DICT_RETURN_EXECUTE);
        vo.setExecuteStatusName(labelOf(executeDict, ro.getExecuteStatus()));
        vo.setExecuteStatusStyle(styleOf(executeDict, ro.getExecuteStatus()));
        // 审批中回填当前节点名（Flowable 当前活跃任务名）
        if ("APPROVING".equals(ro.getApprovalStatus()) && ro.getProcessInstanceId() != null) {
            List<TaskInfo> tasks = engine.listTasks(ro.getProcessInstanceId());
            if (tasks != null && !tasks.isEmpty()) {
                // V75：只有多节点流程才回填「当前节点名」（本流程三级审批，会回填）
                if (FlowNodeUtil.isMultiNode(processDefMapper, RETURN_PROCESS_CODE)) {
                    vo.setCurrentNodeName(tasks.get(0).getTaskName());
                }
            }
        }
    }

    private List<ReturnLineVO> lineVOs(Long returnId) {
        List<ReturnLine> lines = returnLineMapper.selectList(new LambdaQueryWrapper<ReturnLine>()
                .eq(ReturnLine::getReturnId, returnId)
                .orderByAsc(ReturnLine::getId));
        if (lines.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, String> itemNames = itemMapper.selectList(new LambdaQueryWrapper<Item>()
                        .in(Item::getId, lines.stream().map(ReturnLine::getItemId)
                                .collect(Collectors.toSet()))
                        .select(Item::getId, Item::getItemName))
                .stream().collect(Collectors.toMap(Item::getId, Item::getItemName, (a, b) -> a));
        List<ReturnLineVO> vos = new ArrayList<>(lines.size());
        for (ReturnLine ln : lines) {
            ReturnLineVO vo = new ReturnLineVO();
            vo.setId(ln.getId());
            vo.setShipmentLineId(ln.getShipmentLineId());
            vo.setItemId(ln.getItemId());
            vo.setItemName(itemNames.get(ln.getItemId()));
            vo.setReturnQty(ln.getReturnQty());
            vo.setUnitPrice(ln.getUnitPrice());
            vo.setLineAmount(ln.getLineAmount());
            vos.add(vo);
        }
        return vos;
    }

    /** 冲减明细实时拼（数据落库即事实，不依赖 ext 快照） */
    private ReturnWriteoffDetailVO buildWriteoffDetail(ReturnOrder ro) {
        ReturnWriteoffDetailVO vo = new ReturnWriteoffDetailVO();
        Receivable origin = receivableMapper.selectOne(new LambdaQueryWrapper<Receivable>()
                .eq(Receivable::getShipmentId, ro.getShipmentId()));
        if (origin != null) {
            vo.setOriginalReceivableNo(origin.getReceivableNo());
        }
        Receivable red = receivableMapper.selectOne(new LambdaQueryWrapper<Receivable>()
                .eq(Receivable::getReturnId, ro.getId())
                .eq(Receivable::getDocDirection, "RED"));
        if (red != null) {
            vo.setRedReceivableId(red.getId());
            vo.setRedReceivableNo(red.getReceivableNo());
            vo.setRedReceivableAmount(red.getReceivableAmount());
        }
        Invoice redIv = invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getReturnId, ro.getId())
                .eq(Invoice::getDocDirection, "RED"));
        if (redIv != null) {
            vo.setRedInvoiceId(redIv.getId());
            vo.setRedInvoiceNo(redIv.getInvoiceNo());
            vo.setRedInvoiceAmount(redIv.getInvoiceAmount());
            Invoice blue = invoiceMapper.selectById(redIv.getOriginalInvoiceId());
            if (blue != null) {
                vo.setOriginalInvoiceNo(blue.getInvoiceNo());
            }
        }
        Receipt redRc = receiptMapper.selectOne(new LambdaQueryWrapper<Receipt>()
                .eq(Receipt::getReturnId, ro.getId())
                .eq(Receipt::getDocDirection, "RED"));
        if (redRc != null) {
            vo.setRedReceiptId(redRc.getId());
            vo.setRedReceiptNo(redRc.getReceiptNo());
            vo.setRefundAmount(redRc.getReceiptAmount());
            Receipt blue = receiptMapper.selectById(redRc.getOriginalReceiptId());
            if (blue != null) {
                vo.setOriginalReceiptNo(blue.getReceiptNo());
            }
            List<WriteOff> rows = writeOffMapper.selectList(new LambdaQueryWrapper<WriteOff>()
                    .eq(WriteOff::getReceiptId, redRc.getId())
                    .eq(WriteOff::getStatus, "NORMAL"));
            vo.setReverseWriteOffNos(rows.stream().map(WriteOff::getWriteOffNo)
                    .collect(Collectors.toList()));
        }
        Map<String, Object> ext = ro.getExt();
        if (ext != null && ext.get("returnExecuteNotes") instanceof List<?> notes) {
            vo.setExecutionNotes(notes.stream().map(Object::toString).collect(Collectors.toList()));
        }
        return vo;
    }

    // ===================== 内部：字典/通知/工具 =====================

    private Map<String, DictData> dictMap(String typeCode) {
        List<DictData> list = dictDataMapper.selectList(new LambdaQueryWrapper<DictData>()
                .eq(DictData::getTypeCode, typeCode));
        return list.stream().collect(Collectors.toMap(DictData::getDictValue, x -> x, (a, b) -> a));
    }

    private String labelOf(Map<String, DictData> dict, String value) {
        DictData d = value == null ? null : dict.get(value);
        return d != null ? d.getDictLabel() : value;
    }

    private String styleOf(Map<String, DictData> dict, String value) {
        DictData d = value == null ? null : dict.get(value);
        return d != null ? d.getStyle() : null;
    }

    /** 当前用户的待办任务 businessKey → taskId（审批中单回填「直接审批」入口） */
    private Map<String, String> myPendingTaskMap(String username) {
        if (username == null) {
            return Collections.emptyMap();
        }
        try {
            return engine.listMyTasks(username).stream()
                    .filter(t -> RETURN_PROCESS_CODE.equals(t.getProcessDefinitionKey()))
                    .collect(Collectors.toMap(TaskInfo::getBizKey, TaskInfo::getTaskId, (a, b) -> a));
        } catch (Exception e) {
            log.warn("[return] 查待办任务失败 user={}: {}", username, e.toString());
            return Collections.emptyMap();
        }
    }

    private List<Long> resolveRoleUserIds(String roleCode) {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleCode, roleCode));
        if (role == null) {
            return Collections.emptyList();
        }
        List<UserRole> urs = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRoleId, role.getId()));
        if (urs.isEmpty()) {
            return Collections.emptyList();
        }
        return urs.stream().map(UserRole::getUserId).distinct().collect(Collectors.toList());
    }

    private void notifyRole(String code, String bizType, Long bizId, String linkUrl,
                            String roleCode, Long operatorId, Map<String, String> vars) {
        List<Long> receivers = resolveRoleUserIds(roleCode);
        if (receivers.isEmpty()) {
            log.warn("[return] 通知无接收人：role={} 下无用户 code={} bizId={}", roleCode, code, bizId);
            return;
        }
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode(code);
        cmd.setReceivers(receivers);
        cmd.setBizType(bizType);
        cmd.setBizId(String.valueOf(bizId));
        cmd.setLinkUrl(linkUrl);
        cmd.setDedupKey(code + ":" + bizType + ":" + bizId);
        cmd.setVars(vars);
        cmd.setOperatorId(operatorId);
        notificationService.notify(cmd);
    }

    private Map<String, String> returnVars(ReturnOrder ro) {
        Project p = projectMapper.selectById(ro.getProjectId());
        Map<String, String> vars = new HashMap<>();
        vars.put("returnNo", ro.getReturnNo() == null ? "" : ro.getReturnNo());
        vars.put("projectName", p != null && p.getProjectName() == null ? "" : p.getProjectName());
        vars.put("amount", ro.getReturnAmount() == null ? "0" : ro.getReturnAmount().toPlainString());
        vars.put("applicant", currentUsername() == null ? "" : currentUsername());
        return vars;
    }

    /** 提交审批：通知第一节点审批人（主管） */
    private void notifySubmitted(ReturnOrder ro) {
        Map<String, String> vars = returnVars(ro);
        vars.put("applicant", applicantName(ro));
        notifyRole("RETURN_SUBMITTED", "RETURN", ro.getId(), "/approval",
                ROLE_SUPERVISOR, currentUserId(), vars);
    }

    /** 中间节点通过：通知下一级审批人 */
    private void notifyNextApprover(ReturnOrder ro, String roleCode) {
        notifyRole("RETURN_SUBMITTED", "RETURN", ro.getId(), "/approval",
                roleCode, currentUserId(), returnVars(ro));
    }

    /** 末节点通过：通知申请人 */
    private void notifyApproved(ReturnOrder ro) {
        if (ro.getApplicantId() == null) {
            return;
        }
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("RETURN_APPROVED");
        cmd.setReceivers(List.of(ro.getApplicantId()));
        cmd.setBizType("RETURN");
        cmd.setBizId(String.valueOf(ro.getId()));
        cmd.setLinkUrl("/return");
        cmd.setDedupKey("RETURN_APPROVED:RETURN:" + ro.getId());
        cmd.setVars(returnVars(ro));
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    /** 驳回：通知申请人 */
    private void notifyRejected(ReturnOrder ro, String approver, String comment) {
        if (ro.getApplicantId() == null) {
            return;
        }
        Map<String, String> vars = returnVars(ro);
        vars.put("approver", approver == null ? "" : approver);
        vars.put("reason", comment == null ? "" : comment);
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("RETURN_REJECTED");
        cmd.setReceivers(List.of(ro.getApplicantId()));
        cmd.setBizType("RETURN");
        cmd.setBizId(String.valueOf(ro.getId()));
        cmd.setLinkUrl("/return");
        cmd.setDedupKey("RETURN_REJECTED:RETURN:" + ro.getId());
        cmd.setVars(vars);
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    /** 撤销：通知已审批人（无人审批过则跳过） */
    private void notifyWithdrawn(ReturnOrder ro, List<Long> approvedUserIds) {
        if (approvedUserIds == null || approvedUserIds.isEmpty()) {
            log.debug("[return] 撤销时尚无人审批，跳过通知 returnId={}", ro.getId());
            return;
        }
        Map<String, String> vars = returnVars(ro);
        vars.put("operator", currentUsername() == null ? "" : currentUsername());
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("RETURN_WITHDRAWN");
        cmd.setReceivers(approvedUserIds);
        cmd.setBizType("RETURN");
        cmd.setBizId(String.valueOf(ro.getId()));
        cmd.setLinkUrl("/return");
        cmd.setDedupKey("RETURN_WITHDRAWN:RETURN:" + ro.getId());
        cmd.setVars(vars);
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    private String applicantName(ReturnOrder ro) {
        if (ro.getApplicantId() == null) {
            return "";
        }
        User u = userMapper.selectById(ro.getApplicantId());
        return u == null || u.getUsername() == null ? "" : u.getUsername();
    }

    // ===================== 内部：单号生成（count+1 范式，同构既有各模块） =====================

    private String generateReturnNo() {
        String date = LocalDateTime.now().format(RETURN_NO_DATE_FMT);
        Long count = returnOrderMapper.selectCount(new LambdaQueryWrapper<ReturnOrder>()
                .likeRight(ReturnOrder::getReturnNo, RETURN_NO_PREFIX + "-" + date + "-"));
        return String.format("%s-%s-%03d", RETURN_NO_PREFIX, date, (count == null ? 0 : count) + 1);
    }

    private String generateReceivableNo() {
        String date = LocalDateTime.now().format(RETURN_NO_DATE_FMT);
        Long count = receivableMapper.selectCount(new LambdaQueryWrapper<Receivable>()
                .likeRight(Receivable::getReceivableNo, "YS-" + date + "-"));
        return String.format("YS-%s-%03d", date, (count == null ? 0 : count) + 1);
    }

    private String generateReceiptNo() {
        String date = LocalDateTime.now().format(RETURN_NO_DATE_FMT);
        Long count = receiptMapper.selectCount(new LambdaQueryWrapper<Receipt>()
                .likeRight(Receipt::getReceiptNo, "SK-" + date + "-"));
        return String.format("SK-%s-%03d", date, (count == null ? 0 : count) + 1);
    }

    private String generateInvoiceNo() {
        String date = LocalDateTime.now().format(RETURN_NO_DATE_FMT);
        Long count = invoiceMapper.selectCount(new LambdaQueryWrapper<Invoice>()
                .likeRight(Invoice::getInvoiceNo, "FP-" + date + "-"));
        return String.format("FP-%s-%03d", date, (count == null ? 0 : count) + 1);
    }

    private String generateAllocationNo() {
        String date = LocalDateTime.now().format(RETURN_NO_DATE_FMT);
        Long count = invoiceAllocationMapper.selectCount(new LambdaQueryWrapper<InvoiceAllocation>()
                .likeRight(InvoiceAllocation::getAllocationNo, "IA-" + date + "-"));
        return String.format("IA-%s-%03d", date, (count == null ? 0 : count) + 1);
    }

    private String generateWriteOffNo() {
        String date = LocalDateTime.now().format(RETURN_NO_DATE_FMT);
        Long count = writeOffMapper.selectCount(new LambdaQueryWrapper<WriteOff>()
                .likeRight(WriteOff::getWriteOffNo, "WO-" + date + "-"));
        return String.format("WO-%s-%03d", date, (count == null ? 0 : count) + 1);
    }

    // ===================== 内部：项目汇总列维护（同构 V65 各模块私有件） =====================

    /** 项目应收汇总列 += delta（红字单传负值） */
    private void addToProjectReceivable(Long projectId, BigDecimal delta) {
        Project p = projectMapper.selectById(projectId);
        if (p == null || delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        p.setReceivableAmount(nz(p.getReceivableAmount()).add(delta));
        projectMapper.updateById(p);
    }

    /** 项目开票汇总列（RED 做减法，同构 InvoiceServiceImpl.accumulateProjectInvoiced） */
    private void accumulateProjectInvoiced(Long projectId, BigDecimal amount, String docDirection) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            return;
        }
        BigDecimal cur = nz(p.getInvoicedAmount());
        boolean red = "RED".equals(docDirection);
        p.setInvoicedAmount(red ? cur.subtract(amount) : cur.add(amount));
        projectMapper.updateById(p);
    }

    /** 项目回款汇总列（RED 做减法，同构 ReceiptServiceImpl.accumulateProjectCollected） */
    private void accumulateProjectCollected(Long projectId, BigDecimal amount, String docDirection) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            return;
        }
        BigDecimal cur = nz(p.getCollectedAmount());
        boolean red = "RED".equals(docDirection);
        p.setCollectedAmount(red ? cur.subtract(amount) : cur.add(amount));
        projectMapper.updateById(p);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private Long currentUserId() {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, currentUsername()).select(User::getId));
        return u == null ? null : u.getId();
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
