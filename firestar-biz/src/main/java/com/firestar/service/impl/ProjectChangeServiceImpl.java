package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.dto.ChangeCreateReq;
import com.firestar.dto.ChangeItemReq;
import com.firestar.dto.ChangeItemVO;
import com.firestar.dto.ChangeVO;
import com.firestar.dto.NotifyCommand;
import com.firestar.dto.TaskInfo;
import com.firestar.dto.TimelineNodeVO;
import com.firestar.entity.DictData;
import com.firestar.entity.Item;
import com.firestar.entity.Project;
import com.firestar.entity.ProjectChange;
import com.firestar.entity.ProjectChangeItem;
import com.firestar.entity.Role;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.mapper.DictDataMapper;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.ProjectChangeItemMapper;
import com.firestar.mapper.ProjectChangeMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.NotificationService;
import com.firestar.service.ProjectChangeService;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 立项项目变更服务实现（V220）。
 *
 * <h3>为什么要有这张单</h3>
 * <p>立项合同金额手填，手滑多打 0（10 万填成 100 万）导致 90 万无明细支撑、发不出去也核不平。
 * 虎哥 2026-11-23 定案：不改手填习惯，但把「改金额 / 增减明细」收进一条独立受控审批链，
 * 审批通过后以增量 diff 应用到项目——既保留手的弹性，又让每次改动可追溯、可复核。</p>
 *
 * <h3>窗口 A / B（applyChange 时的硬约束）</h3>
 * <ul>
 *   <li><b>窗口 A</b>（无发货/应收/开票/收款）：金额与明细自由改，改完账天然自平；</li>
 *   <li><b>窗口 B</b>（已有下游单据）：新金额不得低于已开票/已收款金额中的较大者；
 *       明细删除仅允许 shippedQty=0 的行；MODIFY 的 quantity 不得低于 shippedQty。</li>
 * </ul>
 *
 * <h3>增量 diff（不是删除重建）</h3>
 * <p>fin_item.shippedQty 记录已发占用，删除重建会丢占用导致超发校验失效。
 * ADD 插新行、MODIFY 只改未发部分对应的字段、DELETE 只删未发行。</p>
 */
@Slf4j
@Service
public class ProjectChangeServiceImpl implements ProjectChangeService {

    /** 变更审批流程定义 key（sys_process_def.process_code） */
    public static final String CHANGE_PROCESS_CODE = "project_change";

    private static final String ROLE_SUPERVISOR = "ROLE_SUPERVISOR";
    private static final String ROLE_SALES_MANAGER = "ROLE_SALES_MANAGER";

    private static final String CHANGE_NO_PREFIX = "CHG";
    private static final DateTimeFormatter CHANGE_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String DICT_APPROVAL_STATUS = "approval_status";

    private final ProjectChangeMapper changeMapper;
    private final ProjectChangeItemMapper changeItemMapper;
    private final ProjectMapper projectMapper;
    private final ItemMapper itemMapper;
    private final DictDataMapper dictDataMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final WorkflowEngineService engine;
    private final NotificationService notificationService;

    @Autowired
    public ProjectChangeServiceImpl(ProjectChangeMapper changeMapper,
                                    ProjectChangeItemMapper changeItemMapper,
                                    ProjectMapper projectMapper,
                                    ItemMapper itemMapper,
                                    DictDataMapper dictDataMapper,
                                    UserMapper userMapper,
                                    RoleMapper roleMapper,
                                    UserRoleMapper userRoleMapper,
                                    WorkflowEngineService engine,
                                    NotificationService notificationService) {
        this.changeMapper = changeMapper;
        this.changeItemMapper = changeItemMapper;
        this.projectMapper = projectMapper;
        this.itemMapper = itemMapper;
        this.dictDataMapper = dictDataMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.engine = engine;
        this.notificationService = notificationService;
    }

    // ===================== 发起变更 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long projectId, ChangeCreateReq req) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new IllegalArgumentException("项目不存在：" + projectId);
        }
        // 归属校验：仅申请人本人或管理员可发起变更（与项目 update 同口径）
        if (!isAdmin() && !Objects.equals(p.getApplicantUsername(), currentUsername())) {
            throw new IllegalStateException("无权发起该项目的变更");
        }
        // 状态闸：仅审批通过的项目可变更（草稿/驳回走原 update；审批中/已拒绝不在此入口）
        if (!"APPROVED".equals(p.getApprovalStatus())) {
            throw new IllegalStateException("当前项目状态不可发起变更：" + p.getApprovalStatus());
        }
        if (req.getChangeReason() == null || req.getChangeReason().isBlank()) {
            throw new IllegalArgumentException("变更原因不能为空");
        }
        // 至少要改一样：金额或明细
        boolean amountChanged = req.getNewContractAmount() != null;
        boolean itemsChanged = req.getItems() != null && !req.getItems().isEmpty();
        if (!amountChanged && !itemsChanged) {
            throw new IllegalArgumentException("金额与明细至少变更一项");
        }
        if (amountChanged && req.getNewContractAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("合同金额不能为负");
        }

        // 判定窗口 A/B：是否存在下游单据（发货/应收/开票/收款）
        String window = detectWindow(p);

        // 明细逐行校验前置于插单：窗口 B 数量不得低于已发量在提交阶段即拦截，
        // 校验失败时不落变更单、不拉审批（而不是靠事务回滚兜底）
        if (itemsChanged) {
            for (ChangeItemReq it : req.getItems()) {
                validateChangeItem(it, window, projectId);
            }
        }

        ProjectChange change = new ProjectChange();
        change.setProjectId(projectId);
        change.setChangeReason(req.getChangeReason().trim());
        change.setOldContractAmount(p.getContractAmount());
        change.setNewContractAmount(req.getNewContractAmount());
        change.setWindowType(window);
        change.setApprovalStatus("DRAFT");
        change.setExecuteStatus("PENDING");
        change.setApplicantId(currentUserId());
        change.setAppliedAt(LocalDateTime.now());
        change.setCreatedBy(currentUserId());
        // 先插单拿自增 ID：change_no 与 Flowable businessKey 都要用
        changeMapper.insert(change);
        change.setChangeNo(generateChangeNo(change.getId()));
        changeMapper.updateById(change);

        // 明细 diff 落库（校验已在插单前统一做完）
        if (itemsChanged) {
            for (ChangeItemReq it : req.getItems()) {
                ProjectChangeItem ci = new ProjectChangeItem();
                ci.setChangeId(change.getId());
                ci.setOpType(it.getOpType());
                ci.setItemId(it.getItemId());
                ci.setItemName(it.getItemName());
                ci.setSpecModel(it.getSpecModel());
                ci.setUnit(it.getUnit());
                ci.setQuantity(it.getQuantity());
                ci.setUnitPrice(it.getUnitPrice());
                ci.setGoodsId(it.getGoodsId());
                ci.setCreatedBy(currentUserId());
                changeItemMapper.insert(ci);
            }
        }

        // 发起 project_change 两级审批（businessKey = 变更单 ID）
        Map<String, Object> vars = new HashMap<>();
        vars.put("applicantUsername", currentUsername());
        vars.put("applicantId", currentUserId());
        String instanceId = engine.start(CHANGE_PROCESS_CODE, String.valueOf(change.getId()), vars);
        change.setProcessInstanceId(instanceId);
        change.setApprovalStatus("APPROVING");
        changeMapper.updateById(change);

        log.info("[change] 创建并发起审批 id={} no={} projectId={} window={} amount={}→{}",
                change.getId(), change.getChangeNo(), projectId, window,
                change.getOldContractAmount(), change.getNewContractAmount());
        notifySubmitted(change);
        return change.getId();
    }

    /** 生成变更单号 CHG-{yyyyMMdd}-{id 四位补齐} */
    private String generateChangeNo(Long changeId) {
        return String.format("%s-%s-%04d", CHANGE_NO_PREFIX,
                LocalDateTime.now().format(CHANGE_NO_DATE_FMT), changeId);
    }

    /**
     * 判定变更窗口：项目是否已有下游单据（发货 / 应收 / 开票 / 收款）。
     * 有任一即窗口 B（受限），全无则窗口 A（自由）。
     */
    private String detectWindow(Project p) {
        boolean hasShipment = itemMapper.selectCount(new LambdaQueryWrapper<Item>()
                .eq(Item::getProjectId, p.getId())
                .gt(Item::getShippedQty, 0)) > 0;
        boolean hasReceivable = p.getReceivableAmount() != null && p.getReceivableAmount().compareTo(BigDecimal.ZERO) > 0;
        boolean hasInvoiced = p.getInvoicedAmount() != null && p.getInvoicedAmount().compareTo(BigDecimal.ZERO) > 0;
        boolean hasCollected = p.getCollectedAmount() != null && p.getCollectedAmount().compareTo(BigDecimal.ZERO) > 0;
        return (hasShipment || hasReceivable || hasInvoiced || hasCollected) ? "B" : "A";
    }

    /**
     * 明细 diff 行校验（发起时做一次，apply 时窗口 B 再校验一次）。
     *
     * <p>窗口 B 的「数量不得低于已发量」在此前移到 create 提交时拦截（原来只在 apply 末节点
     * 通过后校验，会出现审批走完才失败）；单价维持现状（仅 >0，不锁已发部分价格）。
     * 校验原明细归属避免跨项目 itemId 绕过。</p>
     */
    private void validateChangeItem(ChangeItemReq it, String window, Long projectId) {
        String op = it.getOpType();
        if (!"ADD".equals(op) && !"MODIFY".equals(op) && !"DELETE".equals(op)) {
            throw new IllegalArgumentException("明细操作类型非法：" + op + "（须 ADD/MODIFY/DELETE）");
        }
        if (!"DELETE".equals(op)) {
            if (it.getItemName() == null || it.getItemName().isBlank()) {
                throw new IllegalArgumentException("货物/服务名称不能为空");
            }
            if (it.getQuantity() == null || it.getQuantity() <= 0) {
                throw new IllegalArgumentException("数量必须大于 0：" + it.getItemName());
            }
            if (it.getUnitPrice() == null || it.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("单价必须大于 0：" + it.getItemName());
            }
        }
        if (("MODIFY".equals(op) || "DELETE".equals(op)) && it.getItemId() == null) {
            throw new IllegalArgumentException("MODIFY/DELETE 必须指定原明细行 ID（itemId）");
        }
        // 窗口 B：MODIFY 数量不得低于已发量（前移拦截）；DELETE 已发占用在 apply 时仍再校验一次
        if ("B".equals(window) && "MODIFY".equals(op) && it.getItemId() != null) {
            Item origin = itemMapper.selectById(it.getItemId());
            if (origin == null || !Objects.equals(origin.getProjectId(), projectId)) {
                throw new IllegalArgumentException("变更明细行不存在或不属于该项目：" + it.getItemId());
            }
            int shipped = origin.getShippedQty() == null ? 0 : origin.getShippedQty();
            if (it.getQuantity() != null && it.getQuantity() < shipped) {
                throw new IllegalArgumentException(String.format(
                        "货物「%s」变更数量 %d 不得低于已发数量 %d",
                        origin.getItemName(), it.getQuantity(), shipped));
            }
        }
    }

    // ===================== 审批通过（两节点推进 + 末节点应用变更） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeTask(String taskId, Map<String, Object> variables) {
        Map<String, Object> vars = variables == null ? new HashMap<>() : new HashMap<>(variables);
        Object commentRaw = vars.remove("comment");
        String comment = commentRaw == null ? null : String.valueOf(commentRaw);
        if (comment != null && comment.isBlank()) {
            comment = null;
        }
        // 时序双保险：complete 前先拿 businessKey（末节点通过后实例即终态，runtime 被清理）
        String bizKeyUpfront = engine.businessKeyOfTask(taskId);
        String instanceId = engine.complete(taskId, vars, comment);
        List<TaskInfo> next = engine.listTasks(instanceId);
        String bizKey = bizKeyUpfront != null ? bizKeyUpfront : engine.getBusinessKey(instanceId);
        ProjectChange change = (bizKey != null) ? changeMapper.selectById(Long.parseLong(bizKey)) : null;
        if (change == null) {
            log.warn("[change] 审批通过但未回写：bizKey={} 找不到变更单 instanceId={}", bizKey, instanceId);
            return;
        }
        if (next.isEmpty()) {
            // 末节点（经理）通过 → 应用变更（增量 diff 落到项目）
            change.setApprovalStatus("APPROVED");
            change.setUpdatedBy(currentUserId());
            changeMapper.updateById(change);
            applyChange(change);
            change.setExecuteStatus("EXECUTED");
            change.setExecutedAt(LocalDateTime.now());
            changeMapper.updateById(change);
            log.info("[change] 审批通过并应用 id={} no={}", change.getId(), change.getChangeNo());
            notifyApproved(change);
        } else {
            // 中间节点（主管）通过：仍 APPROVING，通知下一级经理
            change.setUpdatedBy(currentUserId());
            changeMapper.updateById(change);
            notifyRole("CHANGE_SUBMITTED", "PROCHANGE", change.getId(), "/approval",
                    ROLE_SALES_MANAGER, currentUserId(), changeVars(change));
            log.info("[change] 审批推进 id={} nextNode={}", change.getId(), next.get(0).getNodeId());
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
            log.warn("[change] 驳回时取不到业务键 taskId={}", taskId);
            return;
        }
        ProjectChange change = changeMapper.selectById(Long.parseLong(bizKey));
        if (change == null) {
            log.warn("[change] 驳回时找不到变更单 bizKey={} taskId={}", bizKey, taskId);
            return;
        }
        if (change.getProcessInstanceId() != null) {
            engine.terminate(change.getProcessInstanceId(), "项目变更被退回：" + comment);
        }
        change.setApprovalStatus("REJECTED");
        change.setExecuteStatus("CANCELLED");
        change.setUpdatedBy(currentUserId());
        changeMapper.updateById(change);
        notifyRejected(change, comment);
        log.info("[change] 审批驳回 id={} comment={}", change.getId(), comment);
    }

    // ===================== 申请人撤销 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        ProjectChange change = changeMapper.selectById(id);
        if (change == null) {
            throw new IllegalArgumentException("变更单不存在：" + id);
        }
        if (!"APPROVING".equals(change.getApprovalStatus())) {
            throw new IllegalStateException("仅审批中的变更单可撤销");
        }
        if (!Objects.equals(change.getApplicantId(), currentUserId()) && !isAdmin()) {
            throw new IllegalStateException("仅申请人可撤销变更单");
        }
        List<Long> approvedUserIds = engine.withdraw(change.getProcessInstanceId(), currentUsername(),
                WorkflowEngineService.WITHDRAW_REASON_PREFIX + "项目变更，变更单 id=" + change.getId());
        change.setApprovalStatus("CANCELLED");
        change.setExecuteStatus("CANCELLED");
        change.setUpdatedBy(currentUserId());
        changeMapper.updateById(change);
        notifyWithdrawn(change, approvedUserIds);
        log.info("[change] 撤销 id={} user={}", id, currentUsername());
    }

    // ===================== 应用变更（核心：增量 diff + 窗口约束） =====================

    /**
     * 把审批通过的变更应用到项目（单事务，由 completeTask 的 @Transactional 包裹）。
     * 顺序：先校验金额下限（窗口 B）→ 应用明细 diff → 应用金额 → 回写项目。
     */
    private void applyChange(ProjectChange change) {
        Project p = projectMapper.selectById(change.getProjectId());
        if (p == null) {
            throw new IllegalStateException("应用变更时项目不存在：" + change.getProjectId());
        }
        List<ProjectChangeItem> diffs = changeItemMapper.selectList(new LambdaQueryWrapper<ProjectChangeItem>()
                .eq(ProjectChangeItem::getChangeId, change.getId())
                .orderByAsc(ProjectChangeItem::getId));

        // 1. 应用明细增量 diff，返回应用后的明细合计
        BigDecimal itemsTotal = applyItemDiffs(p, diffs, change.getWindowType());

        // 2. 计算目标合同金额：newContractAmount 非空则用之，否则用明细合计（仅改明细时自动追平）
        BigDecimal targetAmount = change.getNewContractAmount() != null
                ? change.getNewContractAmount() : itemsTotal;

        // 3. 窗口 B 金额下限：不得低于已开票 / 已收款中的较大者
        if ("B".equals(change.getWindowType())) {
            BigDecimal floor = BigDecimal.ZERO;
            if (p.getInvoicedAmount() != null) {
                floor = floor.max(p.getInvoicedAmount());
            }
            if (p.getCollectedAmount() != null) {
                floor = floor.max(p.getCollectedAmount());
            }
            if (targetAmount.compareTo(floor) < 0) {
                throw new IllegalStateException(String.format(
                        "合同金额不得低于已开票/已收款金额（下限 %s，目标 %s）",
                        floor.toPlainString(), targetAmount.toPlainString()));
            }
        }
        if (targetAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("合同金额不能为负");
        }

        // 4. 回写项目金额（不动 approvalStatus / contractStatus）
        p.setContractAmount(targetAmount);
        p.setUpdatedBy(currentUserId());
        projectMapper.updateById(p);

        log.info("[change] 应用完成 id={} projectId={} window={} 明细合计={} 合同金额={}",
                change.getId(), change.getProjectId(), change.getWindowType(),
                itemsTotal.toPlainString(), targetAmount.toPlainString());
    }

    /**
     * 应用明细增量 diff，返回应用后的项目明细合计。
     *
     * <p>窗口 B 硬约束：DELETE 仅允许 shippedQty=0；MODIFY 的 quantity 不得低于 shippedQty；
     * 且不允许把行的 unitPrice 改成 0 或负（已发部分金额会失真）。</p>
     */
    private BigDecimal applyItemDiffs(Project p, List<ProjectChangeItem> diffs, String window) {
        boolean restricted = "B".equals(window);
        for (ProjectChangeItem d : diffs) {
            String op = d.getOpType();
            if ("ADD".equals(op)) {
                Item entity = new Item();
                entity.setProjectId(p.getId());
                entity.setItemName(d.getItemName().trim());
                entity.setSpecModel(d.getSpecModel());
                entity.setUnit(d.getUnit());
                entity.setQuantity(d.getQuantity());
                entity.setShippedQty(0);
                entity.setUnitPrice(d.getUnitPrice());
                entity.setTotalAmount(d.getUnitPrice().multiply(BigDecimal.valueOf(d.getQuantity())));
                // goodsId：前端服务行传 0；货物行由前端按名称解析后传入，与立项 persistItems 同口径
                entity.setGoodsId(d.getGoodsId() == null ? 0L : d.getGoodsId());
                entity.setCreatedBy(currentUserId());
                itemMapper.insert(entity);

            } else if ("MODIFY".equals(op)) {
                Item item = itemMapper.selectById(d.getItemId());
                if (item == null || !Objects.equals(item.getProjectId(), p.getId())) {
                    throw new IllegalStateException("变更明细行不存在或不属于该项目：" + d.getItemId());
                }
                int shipped = item.getShippedQty() == null ? 0 : item.getShippedQty();
                int targetQty = d.getQuantity() == null ? item.getQuantity() : d.getQuantity();
                if (restricted && targetQty < shipped) {
                    throw new IllegalStateException(String.format(
                            "货物「%s」变更数量 %d 不得低于已发数量 %d",
                            item.getItemName(), targetQty, shipped));
                }
                item.setQuantity(targetQty);
                if (d.getUnitPrice() != null) {
                    item.setUnitPrice(d.getUnitPrice());
                }
                if (d.getItemName() != null && !d.getItemName().isBlank()) {
                    item.setItemName(d.getItemName().trim());
                }
                if (d.getSpecModel() != null) item.setSpecModel(d.getSpecModel());
                if (d.getUnit() != null) item.setUnit(d.getUnit());
                item.setTotalAmount(item.getUnitPrice() == null ? null
                        : item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
                item.setUpdatedBy(currentUserId());
                itemMapper.updateById(item);

            } else if ("DELETE".equals(op)) {
                Item item = itemMapper.selectById(d.getItemId());
                if (item == null || !Objects.equals(item.getProjectId(), p.getId())) {
                    throw new IllegalStateException("变更明细行不存在或不属于该项目：" + d.getItemId());
                }
                int shipped = item.getShippedQty() == null ? 0 : item.getShippedQty();
                if (shipped > 0) {
                    throw new IllegalStateException(String.format(
                            "货物「%s」已发 %d 件，不允许删除（窗口 B 保护已发占用）",
                            item.getItemName(), shipped));
                }
                itemMapper.deleteById(d.getItemId());
            }
        }
        // 从库内实际明细回算合计（唯一真源，不信任前端传值）
        List<Item> items = itemMapper.selectList(new LambdaQueryWrapper<Item>()
                .eq(Item::getProjectId, p.getId()));
        BigDecimal total = BigDecimal.ZERO;
        for (Item it : items) {
            if (it.getTotalAmount() != null) {
                total = total.add(it.getTotalAmount());
            } else if (it.getUnitPrice() != null && it.getQuantity() != null) {
                total = total.add(it.getUnitPrice().multiply(BigDecimal.valueOf(it.getQuantity())));
            }
        }
        return total;
    }

    // ===================== 查询 =====================

    @Override
    public IPage<ChangeVO> page(Integer pageNum, Integer pageSize, Long projectId,
                                String approvalStatus, String username, boolean viewAll) {
        Page<ProjectChange> page = new Page<>(pageNum == null ? 1 : pageNum, pageSize == null ? 10 : pageSize);
        LambdaQueryWrapper<ProjectChange> w = new LambdaQueryWrapper<>();
        w.eq(projectId != null, ProjectChange::getProjectId, projectId);
        w.eq(approvalStatus != null && !approvalStatus.isBlank(), ProjectChange::getApprovalStatus, approvalStatus);
        if (!viewAll) {
            // 非管理员只看自己发起的变更单
            User me = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, username).select(User::getId));
            w.eq(me != null, ProjectChange::getApplicantId, me == null ? -1L : me.getId());
        }
        w.orderByDesc(ProjectChange::getId);
        IPage<ProjectChange> raw = changeMapper.selectPage(page, w);
        return raw.convert(this::toVO);
    }

    @Override
    public List<ChangeVO> listByProject(Long projectId) {
        List<ProjectChange> list = changeMapper.selectList(new LambdaQueryWrapper<ProjectChange>()
                .eq(ProjectChange::getProjectId, projectId)
                .orderByDesc(ProjectChange::getId));
        String username = currentUsername();
        boolean admin = isAdmin();
        return list.stream().map(c -> {
            ChangeVO vo = toVO(c);
            vo.setMyPendingTaskId(myPendingTaskId(c, username));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public ChangeVO detail(Long id, String username, boolean isAdmin) {
        ProjectChange change = changeMapper.selectById(id);
        if (change == null) {
            throw new IllegalArgumentException("变更单不存在：" + id);
        }
        // 归属校验：申请人 / 审批人 / 管理员可见，其余 404 语义（这里用异常简化）
        if (!isAdmin && !Objects.equals(change.getApplicantId(), currentUserId())
                && !engine.isCandidate(CHANGE_PROCESS_CODE, String.valueOf(id), username)) {
            throw new IllegalStateException("无权查看该变更单");
        }
        ChangeVO vo = toVO(change);
        List<ProjectChangeItem> items = changeItemMapper.selectList(new LambdaQueryWrapper<ProjectChangeItem>()
                .eq(ProjectChangeItem::getChangeId, id)
                .orderByAsc(ProjectChangeItem::getId));
        vo.setItems(items.stream().map(this::toItemVO).collect(Collectors.toList()));
        vo.setTimeline(engine.listTimelineByBusinessKey(CHANGE_PROCESS_CODE, String.valueOf(id)));
        vo.setMyPendingTaskId(myPendingTaskId(change, username));
        vo.setCanCancel("APPROVING".equals(change.getApprovalStatus())
                && Objects.equals(change.getApplicantId(), currentUserId()));
        return vo;
    }

    @Override
    public List<TimelineNodeVO> timeline(Long id) {
        return engine.listTimelineByBusinessKey(CHANGE_PROCESS_CODE, String.valueOf(id));
    }

    /** 当前用户在某变更单上的待审批任务 ID（无则 null） */
    private String myPendingTaskId(ProjectChange change, String username) {
        if (!"APPROVING".equals(change.getApprovalStatus())) {
            return null;
        }
        List<TaskInfo> tasks = engine.listMyTasks(username);
        return tasks.stream()
                .filter(t -> CHANGE_PROCESS_CODE.equals(t.getProcessDefinitionKey()))
                .filter(t -> String.valueOf(change.getId()).equals(t.getBizKey()))
                .map(TaskInfo::getTaskId)
                .findFirst().orElse(null);
    }

    // ===================== VO 转换 =====================

    private ChangeVO toVO(ProjectChange c) {
        ChangeVO v = new ChangeVO();
        v.setId(c.getId());
        v.setChangeNo(c.getChangeNo());
        v.setProjectId(c.getProjectId());
        v.setChangeReason(c.getChangeReason());
        v.setOldContractAmount(c.getOldContractAmount());
        v.setNewContractAmount(c.getNewContractAmount());
        v.setWindowType(c.getWindowType());
        v.setApprovalStatus(c.getApprovalStatus());
        v.setApprovalStatusName(dictLabel(DICT_APPROVAL_STATUS, c.getApprovalStatus()));
        v.setExecuteStatus(c.getExecuteStatus());
        v.setProcessInstanceId(c.getProcessInstanceId());
        v.setApplicantId(c.getApplicantId());
        v.setApplicantName(applicantName(c));
        v.setAppliedAt(c.getAppliedAt());
        v.setExecutedAt(c.getExecutedAt());
        v.setCreatedAt(c.getCreatedAt());
        Project p = projectMapper.selectById(c.getProjectId());
        if (p != null) {
            v.setProjectNo(p.getProjectNo());
            v.setProjectName(p.getProjectName());
        }
        v.setCanCancel("APPROVING".equals(c.getApprovalStatus())
                && Objects.equals(c.getApplicantId(), currentUserId()));
        return v;
    }

    private ChangeItemVO toItemVO(ProjectChangeItem ci) {
        ChangeItemVO v = new ChangeItemVO();
        v.setId(ci.getId());
        v.setChangeId(ci.getChangeId());
        v.setOpType(ci.getOpType());
        v.setItemId(ci.getItemId());
        v.setItemName(ci.getItemName());
        v.setSpecModel(ci.getSpecModel());
        v.setUnit(ci.getUnit());
        v.setQuantity(ci.getQuantity());
        v.setUnitPrice(ci.getUnitPrice());
        v.setGoodsId(ci.getGoodsId());
        return v;
    }

    private String applicantName(ProjectChange c) {
        if (c.getApplicantId() == null) return "";
        User u = userMapper.selectById(c.getApplicantId());
        return u == null ? "" : (u.getNickname() != null ? u.getNickname() : u.getUsername());
    }

    private String dictLabel(String typeCode, String value) {
        if (value == null) return "";
        DictData d = dictDataMapper.selectOne(new LambdaQueryWrapper<DictData>()
                .eq(DictData::getTypeCode, typeCode)
                .eq(DictData::getDictValue, value)
                .last("LIMIT 1"));
        return d == null ? value : d.getDictLabel();
    }

    // ===================== 通知 =====================

    private Map<String, String> changeVars(ProjectChange c) {
        Project p = projectMapper.selectById(c.getProjectId());
        Map<String, String> vars = new HashMap<>();
        vars.put("changeNo", c.getChangeNo() == null ? "" : c.getChangeNo());
        vars.put("projectName", p != null && p.getProjectName() != null ? p.getProjectName() : "");
        String amountDetail = c.getOldContractAmount() == null ? "" : c.getOldContractAmount().toPlainString();
        if (c.getNewContractAmount() != null) {
            amountDetail += " → " + c.getNewContractAmount().toPlainString();
        }
        vars.put("amountDetail", amountDetail);
        vars.put("applicant", currentUsername() == null ? "" : currentUsername());
        return vars;
    }

    /** 提交审批：通知第一节点（主管） */
    private void notifySubmitted(ProjectChange c) {
        notifyRole("CHANGE_SUBMITTED", "PROCHANGE", c.getId(), "/approval",
                ROLE_SUPERVISOR, currentUserId(), changeVars(c));
    }

    /** 末节点通过：通知申请人 */
    private void notifyApproved(ProjectChange c) {
        if (c.getApplicantId() == null) return;
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("CHANGE_APPROVED");
        cmd.setReceivers(List.of(c.getApplicantId()));
        cmd.setBizType("PROCHANGE");
        cmd.setBizId(String.valueOf(c.getId()));
        cmd.setLinkUrl("/project");
        cmd.setDedupKey("CHANGE_APPROVED:PROCHANGE:" + c.getId());
        cmd.setVars(changeVars(c));
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    /** 驳回：通知申请人 */
    private void notifyRejected(ProjectChange c, String reason) {
        if (c.getApplicantId() == null) return;
        Map<String, String> vars = changeVars(c);
        vars.put("approver", currentUsername() == null ? "" : currentUsername());
        vars.put("reason", reason == null ? "" : reason);
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("CHANGE_REJECTED");
        cmd.setReceivers(List.of(c.getApplicantId()));
        cmd.setBizType("PROCHANGE");
        cmd.setBizId(String.valueOf(c.getId()));
        cmd.setLinkUrl("/project");
        cmd.setDedupKey("CHANGE_REJECTED:PROCHANGE:" + c.getId());
        cmd.setVars(vars);
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    /** 撤销：通知已审批过的人 */
    private void notifyWithdrawn(ProjectChange c, List<Long> approvedUserIds) {
        if (approvedUserIds == null || approvedUserIds.isEmpty()) return;
        Map<String, String> vars = changeVars(c);
        vars.put("operator", currentUsername() == null ? "" : currentUsername());
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("CHANGE_WITHDRAWN");
        cmd.setReceivers(approvedUserIds);
        cmd.setBizType("PROCHANGE");
        cmd.setBizId(String.valueOf(c.getId()));
        cmd.setLinkUrl("/approval");
        cmd.setDedupKey("CHANGE_WITHDRAWN:PROCHANGE:" + c.getId());
        cmd.setVars(vars);
        cmd.setOperatorId(currentUserId());
        notificationService.notify(cmd);
    }

    private void notifyRole(String code, String bizType, Long bizId, String linkUrl,
                            String roleCode, Long operatorId, Map<String, String> vars) {
        List<Long> receivers = resolveRoleUserIds(roleCode);
        if (receivers.isEmpty()) {
            log.warn("[change] 通知无接收人：role={} code={} bizId={}", roleCode, code, bizId);
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

    private List<Long> resolveRoleUserIds(String roleCode) {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleCode, roleCode));
        if (role == null) return Collections.emptyList();
        List<UserRole> urs = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRoleId, role.getId()));
        if (urs.isEmpty()) return Collections.emptyList();
        return urs.stream().map(UserRole::getUserId).distinct().collect(Collectors.toList());
    }

    // ===================== 鉴权辅助 =====================

    private Long currentUserId() {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, currentUsername()).select(User::getId));
        return u == null ? null : u.getId();
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private boolean isAdmin() {
        return com.firestar.common.SecurityContextUtils.isAdmin();
    }
}
