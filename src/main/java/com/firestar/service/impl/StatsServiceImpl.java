package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.ArSummaryVO;
import com.firestar.dto.FulfillmentVO;
import com.firestar.dto.InvoiceSummaryVO;
import com.firestar.dto.OverviewVO;
import com.firestar.dto.ProjectRankingRowVO;
import com.firestar.dto.ProjectRankingVO;
import com.firestar.dto.StatsQuery;
import com.firestar.entity.Customer;
import com.firestar.entity.Invoice;
import com.firestar.entity.Project;
import com.firestar.entity.Receipt;
import com.firestar.entity.Receivable;
import com.firestar.entity.ReturnOrder;
import com.firestar.entity.Shipment;
import com.firestar.entity.User;
import com.firestar.entity.WriteOff;
import com.firestar.mapper.CustomerMapper;
import com.firestar.mapper.InvoiceMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceiptMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.ReturnOrderMapper;
import com.firestar.mapper.ShipmentMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.WriteOffMapper;
import com.firestar.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

/**
 * 看板只读统计层实现（L1）。
 *
 * <p>口径红线（详见 docs/数据看板-指标体系设计.md §6）：
 * <ul>
 *   <li>禁用 fin_receivable.written_off_amount / unwritten_off_amount（全库无写入方）；
 *       应收余额/待开票一律用 V65 的 unreceived_amount / uninvoiced_amount；</li>
 *   <li>回款只认 approval_status='RECEIVED'；红字（doc_direction='RED'）与退款（receipt_type='REFUND'）金额取负；</li>
 *   <li>所有实体继承 LogicDeleteEntity → MP 自动追加 is_deleted=0，本类不再手加；</li>
 *   <li>租户拦截器 V1 未启用（config/MybatisPlusConfig），tenant_id 全 0，与 ProjectServiceImpl.amountsOf 同构，不手加；</li>
 *   <li>项目级过滤下推前先收敛 projectId 集合；空集直接早退，杜绝空集 IN()（见退货 500 教训）。</li>
 * </ul>
 *
 * <p>账龄 / DSO / 应收周转率等由 L2 的 AgeingService 计算，本类不含（ArSummaryVO 已预留字段）。</p>
 */
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final ProjectMapper projectMapper;
    private final CustomerMapper customerMapper;
    private final UserMapper userMapper;
    private final ReceivableMapper receivableMapper;
    private final ReceiptMapper receiptMapper;
    private final InvoiceMapper invoiceMapper;
    private final ShipmentMapper shipmentMapper;
    private final WriteOffMapper writeOffMapper;
    private final ReturnOrderMapper returnOrderMapper;

    // ===================== 工具 =====================

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 红字取负（应收/发票） */
    private static BigDecimal signed(BigDecimal amt, String dir) {
        return "RED".equals(dir) ? nz(amt).negate() : nz(amt);
    }

    /** 收款方向：RED 红冲 或 REFUND 退款 均取负 */
    private static BigDecimal receiptSigned(BigDecimal amt, String dir, String type) {
        boolean neg = "RED".equals(dir) || "REFUND".equals(type);
        return neg ? nz(amt).negate() : nz(amt);
    }

    private static BigDecimal rate(BigDecimal num, BigDecimal den) {
        if (den == null || den.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return num.divide(den, 4, RoundingMode.HALF_UP);
    }

    /** 解析期间为 [start,end]，预设优先；ALL/null → [null,null]（不过滤日期） */
    private static LocalDate[] periodDates(StatsQuery q) {
        LocalDate now = LocalDate.now();
        String preset = q.getPeriodPreset();
        if (preset != null) {
            switch (preset) {
                case "THIS_MONTH":
                    return new LocalDate[]{now.withDayOfMonth(1), now.withDayOfMonth(now.lengthOfMonth())};
                case "THIS_QUARTER": {
                    int qtr = (now.getMonthValue() - 1) / 3;
                    LocalDate start = now.withMonth(qtr * 3 + 1).withDayOfMonth(1);
                    LocalDate end = start.plusMonths(2).withDayOfMonth(start.plusMonths(2).lengthOfMonth());
                    return new LocalDate[]{start, end};
                }
                case "THIS_YEAR":
                    return new LocalDate[]{now.withMonth(1).withDayOfMonth(1), now.withMonth(12).withDayOfMonth(31)};
                case "LAST_12_MONTH":
                    return new LocalDate[]{now.minusMonths(11).withDayOfMonth(1), now.withDayOfMonth(now.lengthOfMonth())};
                case "ALL":
                default:
                    return new LocalDate[]{null, null};
            }
        }
        return new LocalDate[]{q.getPeriodStart(), q.getPeriodEnd()};
    }

    /**
     * 收敛 projectId 集合：
     * - 指定 projectId → [id]
     * - 指定 customerId/managerId → 查 fin_project 收敛
     * - 都未指定 → null（表示不做项目级过滤）
     * 返回空集合表示"过滤后无匹配项目"，调用方应早退返回零值。
     */
    private List<Long> resolveProjectIds(StatsQuery q) {
        if (q.getProjectId() != null) return List.of(q.getProjectId());
        boolean needFilter = q.getCustomerId() != null || q.getManagerId() != null;
        if (!needFilter) return null;
        LambdaQueryWrapper<Project> w = new LambdaQueryWrapper<>();
        if (q.getCustomerId() != null) w.eq(Project::getCustomerId, q.getCustomerId());
        if (q.getManagerId() != null) w.eq(Project::getSalesManagerId, q.getManagerId());
        return projectMapper.selectList(w.select(Project::getId)).stream()
                .map(Project::getId).collect(Collectors.toList());
    }

    /** 项目级过滤下推；pids 为 null 或空则不加（空集合 IN() 会炸 SQL） */
    private static <T> void inProject(LambdaQueryWrapper<T> w, SFunction<T, Long> col, List<Long> pids) {
        if (pids != null && !pids.isEmpty()) w.in(col, pids);
    }

    private static <T> void betweenDate(LambdaQueryWrapper<T> w, SFunction<T, LocalDate> col, LocalDate[] p) {
        if (p[0] != null && p[1] != null) w.between(col, p[0], p[1]);
        else if (p[0] != null) w.ge(col, p[0]);
        else if (p[1] != null) w.le(col, p[1]);
    }

    private static <T> void betweenDateTime(LambdaQueryWrapper<T> w, SFunction<T, LocalDateTime> col, LocalDate[] p) {
        if (p[0] != null && p[1] != null) w.between(col, p[0].atStartOfDay(), p[1].atTime(23, 59, 59));
        else if (p[0] != null) w.ge(col, p[0].atStartOfDay());
        else if (p[1] != null) w.le(col, p[1].atTime(23, 59, 59));
    }

    // ===================== Tab① 经营总览 =====================

    @Override
    public OverviewVO overview(StatsQuery q) {
        OverviewVO v = new OverviewVO();
        List<Long> pids = resolveProjectIds(q);
        LocalDate[] p = periodDates(q);
        if (pids != null && pids.isEmpty()) {
            v.setProjectCount(0L);
            v.setCustomerCount(customerEnabledCount());
            return v;
        }

        // F-01 合同签约额（approved，累计）
        for (Project pr : projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .select(Project::getContractAmount)
                .eq(Project::getApprovalStatus, "APPROVED"))) {
            v.setContractAmount(v.getContractAmount().add(nz(pr.getContractAmount())));
        }

        // F-02 本期发货额（区间，排除 REJECTED）
        {
            LambdaQueryWrapper<Shipment> w = new LambdaQueryWrapper<Shipment>()
                    .select(Shipment::getShipmentAmount)
                    .ne(Shipment::getShipmentStatus, "REJECTED");
            inProject(w, Shipment::getProjectId, pids);
            betweenDateTime(w, Shipment::getShipmentDate, p);
            for (Shipment s : shipmentMapper.selectList(w)) {
                v.setShipmentAmount(v.getShipmentAmount().add(nz(s.getShipmentAmount())));
            }
        }

        // F-03 本期应收发生额（区间，BLUE-RED）
        {
            LambdaQueryWrapper<Receivable> w = new LambdaQueryWrapper<Receivable>()
                    .select(Receivable::getReceivableAmount, Receivable::getDocDirection);
            inProject(w, Receivable::getProjectId, pids);
            betweenDate(w, Receivable::getReceivableDate, p);
            for (Receivable r : receivableMapper.selectList(w)) {
                v.setReceivableOccurred(v.getReceivableOccurred().add(signed(r.getReceivableAmount(), r.getDocDirection())));
            }
        }

        // F-04/F-06 本期开票额/税额（区间，ISSUED，BLUE-RED）
        {
            LambdaQueryWrapper<Invoice> w = new LambdaQueryWrapper<Invoice>()
                    .select(Invoice::getInvoiceAmount, Invoice::getTaxAmount, Invoice::getDocDirection)
                    .eq(Invoice::getInvoiceStatus, "ISSUED");
            inProject(w, Invoice::getProjectId, pids);
            betweenDate(w, Invoice::getInvoiceDate, p);
            for (Invoice iv : invoiceMapper.selectList(w)) {
                v.setInvoiceAmount(v.getInvoiceAmount().add(signed(iv.getInvoiceAmount(), iv.getDocDirection())));
                v.setTaxAmount(v.getTaxAmount().add(nz(iv.getTaxAmount())));
            }
        }

        // F-07 本期回款额（单据口径：区间、RECEIVED、RED/REFUND 取负）
        {
            LambdaQueryWrapper<Receipt> w = new LambdaQueryWrapper<Receipt>()
                    .select(Receipt::getReceiptAmount, Receipt::getDocDirection, Receipt::getReceiptType)
                    .eq(Receipt::getApprovalStatus, "RECEIVED");
            inProject(w, Receipt::getProjectId, pids);
            betweenDate(w, Receipt::getReceiptDate, p);
            for (Receipt rc : receiptMapper.selectList(w)) {
                v.setReceiptAmount(v.getReceiptAmount().add(receiptSigned(rc.getReceiptAmount(), rc.getDocDirection(), rc.getReceiptType())));
            }
        }

        // F-08 本期回款额（核销口径：区间、NORMAL）。
        // 注：fin_write_off 无 projectId 列，无法随主体过滤下推；一期按"公司级"汇总
        // （总览页通常不过滤主体），与 F-07 叠加的差异池在主体过滤下为近似。
        {
            LambdaQueryWrapper<WriteOff> w = new LambdaQueryWrapper<WriteOff>()
                    .select(WriteOff::getAmount).eq(WriteOff::getStatus, "NORMAL");
            betweenDate(w, WriteOff::getWriteOffDate, p);
            for (WriteOff wo : writeOffMapper.selectList(w)) {
                v.setWriteOffAmount(v.getWriteOffAmount().add(nz(wo.getAmount())));
            }
        }

        // B-01 应收余额（存量，unreceived_amount，V65 权威列）
        {
            LambdaQueryWrapper<Receivable> w = new LambdaQueryWrapper<Receivable>()
                    .select(Receivable::getUnreceivedAmount);
            inProject(w, Receivable::getProjectId, pids);
            for (Receivable r : receivableMapper.selectList(w)) {
                v.setArBalance(v.getArBalance().add(nz(r.getUnreceivedAmount())));
            }
        }

        // B-02 待开票余额（存量，uninvoiced_amount）
        {
            LambdaQueryWrapper<Receivable> w = new LambdaQueryWrapper<Receivable>()
                    .select(Receivable::getUninvoicedAmount);
            inProject(w, Receivable::getProjectId, pids);
            for (Receivable r : receivableMapper.selectList(w)) {
                v.setUninvoicedBalance(v.getUninvoicedBalance().add(nz(r.getUninvoicedAmount())));
            }
        }

        // B-03 逾期金额（存量，receivable_date < 今天）
        {
            LambdaQueryWrapper<Receivable> w = new LambdaQueryWrapper<Receivable>()
                    .select(Receivable::getUnreceivedAmount).lt(Receivable::getReceivableDate, LocalDate.now());
            inProject(w, Receivable::getProjectId, pids);
            for (Receivable r : receivableMapper.selectList(w)) {
                v.setOverdueAmount(v.getOverdueAmount().add(nz(r.getUnreceivedAmount())));
            }
        }

        v.setDiffPool(v.getReceiptAmount().subtract(v.getWriteOffAmount()));
        v.setProjectCount(pids != null ? (long) pids.size() : projectMapper.selectCount(new LambdaQueryWrapper<>()));
        v.setCustomerCount(customerEnabledCount());
        return v;
    }

    // ===================== Tab② 应收与回款 =====================

    @Override
    public ArSummaryVO arSummary(StatsQuery q) {
        ArSummaryVO v = new ArSummaryVO();
        List<Long> pids = resolveProjectIds(q);
        LocalDate[] p = periodDates(q);
        if (pids != null && pids.isEmpty()) return v;

        // B-01 应收余额
        {
            LambdaQueryWrapper<Receivable> w = new LambdaQueryWrapper<Receivable>()
                    .select(Receivable::getUnreceivedAmount);
            inProject(w, Receivable::getProjectId, pids);
            for (Receivable r : receivableMapper.selectList(w)) {
                v.setArBalance(v.getArBalance().add(nz(r.getUnreceivedAmount())));
            }
        }

        // B-03 逾期金额
        {
            LambdaQueryWrapper<Receivable> w = new LambdaQueryWrapper<Receivable>()
                    .select(Receivable::getUnreceivedAmount).lt(Receivable::getReceivableDate, LocalDate.now());
            inProject(w, Receivable::getProjectId, pids);
            for (Receivable r : receivableMapper.selectList(w)) {
                v.setOverdueAmount(v.getOverdueAmount().add(nz(r.getUnreceivedAmount())));
            }
        }
        v.setOverdueRate(rate(v.getOverdueAmount(), v.getArBalance()));

        // F-07 本期回款（单据口径）
        {
            LambdaQueryWrapper<Receipt> w = new LambdaQueryWrapper<Receipt>()
                    .select(Receipt::getReceiptAmount, Receipt::getDocDirection, Receipt::getReceiptType)
                    .eq(Receipt::getApprovalStatus, "RECEIVED");
            inProject(w, Receipt::getProjectId, pids);
            betweenDate(w, Receipt::getReceiptDate, p);
            for (Receipt rc : receiptMapper.selectList(w)) {
                v.setReceiptPeriod(v.getReceiptPeriod().add(receiptSigned(rc.getReceiptAmount(), rc.getDocDirection(), rc.getReceiptType())));
            }
        }

        // F-03 本期应收发生额
        {
            LambdaQueryWrapper<Receivable> w = new LambdaQueryWrapper<Receivable>()
                    .select(Receivable::getReceivableAmount, Receivable::getDocDirection);
            inProject(w, Receivable::getProjectId, pids);
            betweenDate(w, Receivable::getReceivableDate, p);
            for (Receivable r : receivableMapper.selectList(w)) {
                v.setReceivablePeriod(v.getReceivablePeriod().add(signed(r.getReceivableAmount(), r.getDocDirection())));
            }
        }
        v.setCollectionRate(rate(v.getReceiptPeriod(), v.getReceivablePeriod()));

        // F-08 本期回款（核销口径）
        {
            LambdaQueryWrapper<WriteOff> w = new LambdaQueryWrapper<WriteOff>()
                    .select(WriteOff::getAmount).eq(WriteOff::getStatus, "NORMAL");
            betweenDate(w, WriteOff::getWriteOffDate, p);
            for (WriteOff wo : writeOffMapper.selectList(w)) {
                v.setWriteOffPeriod(v.getWriteOffPeriod().add(nz(wo.getAmount())));
            }
        }
        v.setDiffPool(v.getReceiptPeriod().subtract(v.getWriteOffPeriod()));
        return v;
    }

    // ===================== Tab③ 开票与票税 =====================

    @Override
    public InvoiceSummaryVO invoiceSummary(StatsQuery q) {
        InvoiceSummaryVO v = new InvoiceSummaryVO();
        List<Long> pids = resolveProjectIds(q);
        LocalDate[] p = periodDates(q);
        if (pids != null && pids.isEmpty()) return v;

        // F-04/F-06 本期开票额/税额（区间，ISSUED，BLUE-RED）
        BigDecimal blue = BigDecimal.ZERO, red = BigDecimal.ZERO;
        {
            LambdaQueryWrapper<Invoice> w = new LambdaQueryWrapper<Invoice>()
                    .select(Invoice::getInvoiceAmount, Invoice::getTaxAmount, Invoice::getDocDirection)
                    .eq(Invoice::getInvoiceStatus, "ISSUED");
            inProject(w, Invoice::getProjectId, pids);
            betweenDate(w, Invoice::getInvoiceDate, p);
            for (Invoice iv : invoiceMapper.selectList(w)) {
                v.setInvoiceAmount(v.getInvoiceAmount().add(signed(iv.getInvoiceAmount(), iv.getDocDirection())));
                v.setTaxAmount(v.getTaxAmount().add(nz(iv.getTaxAmount())));
                if ("RED".equals(iv.getDocDirection())) red = red.add(nz(iv.getInvoiceAmount()));
                else blue = blue.add(nz(iv.getInvoiceAmount()));
            }
        }
        v.setInvoiceAmountNet(v.getInvoiceAmount().subtract(v.getTaxAmount()));
        v.setRedInvoiceRate(rate(red, blue));

        // B-02 待开票余额（存量）
        {
            LambdaQueryWrapper<Receivable> w = new LambdaQueryWrapper<Receivable>()
                    .select(Receivable::getUninvoicedAmount);
            inProject(w, Receivable::getProjectId, pids);
            for (Receivable r : receivableMapper.selectList(w)) {
                v.setUninvoicedBalance(v.getUninvoicedBalance().add(nz(r.getUninvoicedAmount())));
            }
        }

        // D-02 开票率（累计）：Σ 已开票额 / Σ 应收发生额（均全量，受主体过滤）
        BigDecimal invAll = BigDecimal.ZERO, recAll = BigDecimal.ZERO;
        {
            LambdaQueryWrapper<Invoice> w = new LambdaQueryWrapper<Invoice>()
                    .select(Invoice::getInvoiceAmount, Invoice::getDocDirection)
                    .eq(Invoice::getInvoiceStatus, "ISSUED");
            inProject(w, Invoice::getProjectId, pids);
            for (Invoice iv : invoiceMapper.selectList(w)) {
                invAll = invAll.add(signed(iv.getInvoiceAmount(), iv.getDocDirection()));
            }
        }
        {
            LambdaQueryWrapper<Receivable> w = new LambdaQueryWrapper<Receivable>()
                    .select(Receivable::getReceivableAmount, Receivable::getDocDirection);
            inProject(w, Receivable::getProjectId, pids);
            for (Receivable r : receivableMapper.selectList(w)) {
                recAll = recAll.add(signed(r.getReceivableAmount(), r.getDocDirection()));
            }
        }
        v.setInvoiceRate(rate(invAll, recAll));

        // 本期回款额（用于票款对比图，复用 F-07 口径）
        {
            LambdaQueryWrapper<Receipt> w = new LambdaQueryWrapper<Receipt>()
                    .select(Receipt::getReceiptAmount, Receipt::getDocDirection, Receipt::getReceiptType)
                    .eq(Receipt::getApprovalStatus, "RECEIVED");
            inProject(w, Receipt::getProjectId, pids);
            betweenDate(w, Receipt::getReceiptDate, p);
            for (Receipt rc : receiptMapper.selectList(w)) {
                v.setReceiptAmount(v.getReceiptAmount().add(receiptSigned(rc.getReceiptAmount(), rc.getDocDirection(), rc.getReceiptType())));
            }
        }
        return v;
    }

    // ===================== Tab④ 项目与销售 =====================

    @Override
    public ProjectRankingVO projectRanking(StatsQuery q) {
        ProjectRankingVO vo = new ProjectRankingVO();
        List<Long> pids = resolveProjectIds(q);
        if (pids != null && pids.isEmpty()) {
            vo.setRows(new ArrayList<>());
            vo.setTotal(0L);
            return vo;
        }

        LambdaQueryWrapper<Project> pw = new LambdaQueryWrapper<Project>()
                .select(Project::getId, Project::getProjectNo, Project::getProjectName,
                        Project::getCustomerName, Project::getSalesManagerId, Project::getContractAmount);
        if (q.getCustomerId() != null) pw.eq(Project::getCustomerId, q.getCustomerId());
        if (q.getManagerId() != null) pw.eq(Project::getSalesManagerId, q.getManagerId());
        inProject(pw, Project::getId, pids);
        List<Project> projects = projectMapper.selectList(pw);
        if (projects.isEmpty()) {
            vo.setRows(new ArrayList<>());
            vo.setTotal(0L);
            return vo;
        }

        // 一次性分组聚合（避免逐项目 N+1），与 ProjectServiceImpl.amountsOf 同构
        Map<Long, BigDecimal> shipMap = sumByProject(
                shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>()
                        .select(Shipment::getProjectId, Shipment::getShipmentAmount, Shipment::getShipmentStatus)
                        .ne(Shipment::getShipmentStatus, "REJECTED")),
                Shipment::getProjectId, s -> nz(s.getShipmentAmount()), null);
        Map<Long, BigDecimal> recvMap = sumByProject(
                receivableMapper.selectList(new LambdaQueryWrapper<Receivable>()
                        .select(Receivable::getProjectId, Receivable::getReceivableAmount, Receivable::getDocDirection)),
                Receivable::getProjectId, r -> signed(r.getReceivableAmount(), r.getDocDirection()), null);
        Map<Long, BigDecimal> invMap = sumByProject(
                invoiceMapper.selectList(new LambdaQueryWrapper<Invoice>()
                        .select(Invoice::getProjectId, Invoice::getInvoiceAmount, Invoice::getDocDirection)
                        .eq(Invoice::getInvoiceStatus, "ISSUED")),
                Invoice::getProjectId, i -> signed(i.getInvoiceAmount(), i.getDocDirection()), null);
        Map<Long, BigDecimal> receiptMap = sumByProject(
                receiptMapper.selectList(new LambdaQueryWrapper<Receipt>()
                        .select(Receipt::getProjectId, Receipt::getReceiptAmount, Receipt::getDocDirection, Receipt::getReceiptType)
                        .eq(Receipt::getApprovalStatus, "RECEIVED")),
                Receipt::getProjectId, r -> receiptSigned(r.getReceiptAmount(), r.getDocDirection(), r.getReceiptType()), null);

        Map<Long, String> mgrNames = new LinkedHashMap<>();
        List<ProjectRankingRowVO> rows = new ArrayList<>();
        for (Project pr : projects) {
            ProjectRankingRowVO row = new ProjectRankingRowVO();
            row.setProjectId(pr.getId());
            row.setProjectNo(pr.getProjectNo());
            row.setProjectName(pr.getProjectName());
            row.setCustomerName(pr.getCustomerName());
            Long mgr = pr.getSalesManagerId();
            if (mgr != null) {
                row.setManagerName(mgrNames.computeIfAbsent(mgr, id -> {
                    User u = userMapper.selectById(id);
                    return u != null && u.getNickname() != null && !u.getNickname().isBlank()
                            ? u.getNickname() : (u != null ? u.getUsername() : null);
                }));
            }
            BigDecimal contract = nz(pr.getContractAmount());
            BigDecimal ship = shipMap.getOrDefault(pr.getId(), BigDecimal.ZERO);
            BigDecimal recv = recvMap.getOrDefault(pr.getId(), BigDecimal.ZERO);
            BigDecimal inv = invMap.getOrDefault(pr.getId(), BigDecimal.ZERO);
            BigDecimal receipt = receiptMap.getOrDefault(pr.getId(), BigDecimal.ZERO);
            row.setContractAmount(contract);
            row.setShipmentAmount(ship);
            row.setReceivableAmount(recv);
            row.setInvoiceAmount(inv);
            row.setReceiptAmount(receipt);
            row.setShipRate(rate(ship, contract));
            row.setInvoiceRate(rate(inv, recv));
            row.setReceiptRate(rate(receipt, contract));
            rows.add(row);
        }
        rows.sort(Comparator.comparing(ProjectRankingRowVO::getContractAmount).reversed());
        vo.setRows(rows);
        vo.setTotal((long) rows.size());
        return vo;
    }

    /** 通用：按 projectId 分组求和（amtFn 已含方向代数；dirFn 为 null 时不二次取负） */
    private <E> Map<Long, BigDecimal> sumByProject(List<E> rows, SFunction<E, Long> pidFn,
                                                   SFunction<E, BigDecimal> amtFn, SFunction<E, String> dirFn) {
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        for (E e : rows) {
            Long pid = pidFn.apply(e);
            if (pid == null) continue;
            BigDecimal amt = dirFn != null ? signed(amtFn.apply(e), dirFn.apply(e)) : amtFn.apply(e);
            m.merge(pid, amt, BigDecimal::add);
        }
        return m;
    }

    // ===================== Tab⑤ 履约与风控 =====================

    @Override
    public FulfillmentVO fulfillment(StatsQuery q) {
        FulfillmentVO v = new FulfillmentVO();
        List<Long> pids = resolveProjectIds(q);
        LocalDate[] p = periodDates(q);
        if (pids != null && pids.isEmpty()) return v;

        // D-09 退货率 = Σ 已完成退货额 / Σ 已发货额（排除 REJECTED）
        {
            LambdaQueryWrapper<ReturnOrder> w = new LambdaQueryWrapper<ReturnOrder>()
                    .select(ReturnOrder::getReturnAmount).eq(ReturnOrder::getExecuteStatus, "COMPLETED");
            inProject(w, ReturnOrder::getProjectId, pids);
            for (ReturnOrder ro : returnOrderMapper.selectList(w)) {
                v.setReturnAmountTotal(v.getReturnAmountTotal().add(nz(ro.getReturnAmount())));
            }
        }
        {
            LambdaQueryWrapper<Shipment> w = new LambdaQueryWrapper<Shipment>()
                    .select(Shipment::getShipmentAmount).ne(Shipment::getShipmentStatus, "REJECTED");
            inProject(w, Shipment::getProjectId, pids);
            for (Shipment s : shipmentMapper.selectList(w)) {
                v.setShipmentAmountTotal(v.getShipmentAmountTotal().add(nz(s.getShipmentAmount())));
            }
        }
        v.setReturnRate(rate(v.getReturnAmountTotal(), v.getShipmentAmountTotal()));

        // E-04 在途超期批次数：SHIPPED 且 shipment_date < 今天-7 天
        {
            LambdaQueryWrapper<Shipment> w = new LambdaQueryWrapper<Shipment>()
                    .eq(Shipment::getShipmentStatus, "SHIPPED")
                    .lt(Shipment::getShipmentDate, LocalDate.now().minusDays(7).atStartOfDay());
            inProject(w, Shipment::getProjectId, pids);
            v.setInTransitOverdueCount(shipmentMapper.selectCount(w));
        }

        // D-10 红冲率（整体）：|Σ RED 收/票/应收| / Σ BLUE
        BigDecimal blue = BigDecimal.ZERO, red = BigDecimal.ZERO;
        for (Receipt rc : receiptMapper.selectList(new LambdaQueryWrapper<Receipt>()
                .select(Receipt::getReceiptAmount, Receipt::getDocDirection)
                .eq(Receipt::getApprovalStatus, "RECEIVED"))) {
            if ("RED".equals(rc.getDocDirection())) red = red.add(nz(rc.getReceiptAmount()));
            else blue = blue.add(nz(rc.getReceiptAmount()));
        }
        for (Invoice iv : invoiceMapper.selectList(new LambdaQueryWrapper<Invoice>()
                .select(Invoice::getInvoiceAmount, Invoice::getDocDirection)
                .eq(Invoice::getInvoiceStatus, "ISSUED"))) {
            if ("RED".equals(iv.getDocDirection())) red = red.add(nz(iv.getInvoiceAmount()));
            else blue = blue.add(nz(iv.getInvoiceAmount()));
        }
        for (Receivable r : receivableMapper.selectList(new LambdaQueryWrapper<Receivable>()
                .select(Receivable::getReceivableAmount, Receivable::getDocDirection))) {
            if ("RED".equals(r.getDocDirection())) red = red.add(nz(r.getReceivableAmount()));
            else blue = blue.add(nz(r.getReceivableAmount()));
        }
        v.setRedRate(rate(red, blue));
        return v;
    }

    // ===================== 辅助 =====================

    private long customerEnabledCount() {
        return customerMapper.selectCount(new LambdaQueryWrapper<Customer>().eq(Customer::getStatus, "ENABLED"));
    }
}
