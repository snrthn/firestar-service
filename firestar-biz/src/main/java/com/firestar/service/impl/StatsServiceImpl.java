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
import com.firestar.entity.Goods;
import com.firestar.entity.Invoice;
import com.firestar.entity.Item;
import com.firestar.entity.LoginLog;
import com.firestar.entity.Project;
import com.firestar.entity.Receipt;
import com.firestar.entity.Receivable;
import com.firestar.entity.ReturnOrder;
import com.firestar.entity.Shipment;
import com.firestar.entity.ShipmentLine;
import com.firestar.entity.User;
import com.firestar.entity.WriteOff;
import com.firestar.mapper.CustomerMapper;
import com.firestar.mapper.GoodsMapper;
import com.firestar.mapper.InvoiceMapper;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.LoginLogMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceiptMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.ReturnOrderMapper;
import com.firestar.mapper.ShipmentMapper;
import com.firestar.mapper.ShipmentLineMapper;
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

import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import com.firestar.dto.AgeingVO;
import com.firestar.dto.AgeingSegmentVO;
import com.firestar.dto.PlatformStatsVO;

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
    private final GoodsMapper goodsMapper;
    private final UserMapper userMapper;
    private final ReceivableMapper receivableMapper;
    private final ReceiptMapper receiptMapper;
    private final InvoiceMapper invoiceMapper;
    private final ShipmentMapper shipmentMapper;
    private final ShipmentLineMapper shipmentLineMapper;
    private final WriteOffMapper writeOffMapper;
    private final ReturnOrderMapper returnOrderMapper;
    private final ItemMapper itemMapper;
    private final LoginLogMapper loginLogMapper;

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
     * - 指定 goodsId（V71）→ 在明细层 fin_item.goods_id 反查 distinct projectId 收敛
     * - 多过滤并存时求交（例如"某客户 + 某货物"）
     * - 都未指定 → null（表示不做项目级过滤）
     * 返回空集合表示"过滤后无匹配项目"，调用方应早退返回零值。
     */
    private List<Long> resolveProjectIds(StatsQuery q) {
        // 货物过滤在明细层：先按货物反查项目集合
        List<Long> byGoods = null;
        if (q.getGoodsId() != null) {
            byGoods = itemMapper.selectList(new LambdaQueryWrapper<Item>()
                            .select(Item::getProjectId)
                            .eq(Item::getGoodsId, q.getGoodsId()))
                    .stream()
                    .map(Item::getProjectId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        }

        // 客户/经理 → 收敛出的项目集合（与货物集合、单项目三者求交）
        List<Long> byCustomerManager = null;
        if (q.getCustomerId() != null || q.getManagerId() != null) {
            LambdaQueryWrapper<Project> w = new LambdaQueryWrapper<>();
            if (q.getCustomerId() != null) w.eq(Project::getCustomerId, q.getCustomerId());
            if (q.getManagerId() != null) w.eq(Project::getSalesManagerId, q.getManagerId());
            byCustomerManager = projectMapper.selectList(w.select(Project::getId)).stream()
                    .map(Project::getId).collect(Collectors.toList());
        }

        // 指定单项目：必须同时满足 货物集合 与 客户/经理集合（取交），否则空集早退
        // （修复：原逻辑指定 projectId 后直接 return [projectId]，把 customerId/managerId 整个吞掉，
        //  导致「项目+客户」联动筛选时客户条件被静默丢弃，看板明细与总览对不上）
        if (q.getProjectId() != null) {
            if (byGoods != null && !byGoods.contains(q.getProjectId())) return List.of();
            if (byCustomerManager != null && !byCustomerManager.contains(q.getProjectId())) return List.of();
            return List.of(q.getProjectId());
        }

        // 未指定单项目：取 货物集合 ∩ 客户/经理集合（任一为 null 表示该维度不过滤）
        List<Long> pids = null;
        if (byCustomerManager != null) pids = byCustomerManager;
        if (byGoods != null) {
            if (pids == null) {
                pids = byGoods;
            } else {
                Set<Long> gset = new java.util.HashSet<>(byGoods);
                pids = pids.stream().filter(gset::contains).collect(Collectors.toList());
            }
        }
        return pids; // null = 不做过滤；空列表 = 过滤后无匹配
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
            v.setCustomerCount(customerEnabledCount(pids));
            v.setGoodsCount(goodsEnabledCount(pids));
            return v;
        }

        // F-01 合同签约额（approved，随筛选收敛：projectId/客户/经理/货物 + 签约日期区间）
        // 修复前：遍历全部 approved 项目，既不用 pids 也不用期间，导致「按项目/期间筛选」对 F-01 完全失效；
        //         与 F-02..F-08 的 AND 收敛口径不一致，筛选无匹配时也无法归零。
        {
            LambdaQueryWrapper<Project> w = new LambdaQueryWrapper<Project>()
                    .select(Project::getContractAmount)
                    .eq(Project::getApprovalStatus, "APPROVED");
            inProject(w, Project::getId, pids);
            betweenDateTime(w, Project::getContractSignedAt, p);
            for (Project pr : projectMapper.selectList(w)) {
                v.setContractAmount(v.getContractAmount().add(nz(pr.getContractAmount())));
            }
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
                    .eq(Invoice::getApprovalStatus, "ISSUED");
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
                    .eq(Receipt::getReceiptStatus, "RECEIVED");
            inProject(w, Receipt::getProjectId, pids);
            betweenDate(w, Receipt::getReceiptDate, p);
            for (Receipt rc : receiptMapper.selectList(w)) {
                v.setReceiptAmount(v.getReceiptAmount().add(receiptSigned(rc.getReceiptAmount(), rc.getDocDirection(), rc.getReceiptType())));
            }
        }

        // F-08 本期回款额（核销口径：区间、NORMAL）。V80 起 fin_write_off 带 project_id，
        // 已可随项目/客户/经理筛选下推（与 F-02..F-07、B-01..B-03 口径一致）。
        {
            LambdaQueryWrapper<WriteOff> w = new LambdaQueryWrapper<WriteOff>()
                    .select(WriteOff::getAmount).eq(WriteOff::getStatus, "NORMAL");
            inProject(w, WriteOff::getProjectId, pids);
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
        v.setCustomerCount(customerEnabledCount(pids));
        v.setGoodsCount(goodsEnabledCount(pids));
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
                    .eq(Receipt::getReceiptStatus, "RECEIVED");
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

        // F-08 本期回款（核销口径）。V80 起 write_off 带 project_id，随项目/客户/经理下推
        {
            LambdaQueryWrapper<WriteOff> w = new LambdaQueryWrapper<WriteOff>()
                    .select(WriteOff::getAmount).eq(WriteOff::getStatus, "NORMAL");
            inProject(w, WriteOff::getProjectId, pids);
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
                    .eq(Invoice::getApprovalStatus, "ISSUED");
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
                    .eq(Invoice::getApprovalStatus, "ISSUED");
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
                    .eq(Receipt::getReceiptStatus, "RECEIVED");
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
                        Project::getCustomerId, Project::getSalesManagerId, Project::getContractAmount)
                // 拍板：Tab④ 仅排「已签合同」(APPROVED)，与 F-01 合同签约额口径对齐
                .eq(Project::getApprovalStatus, "APPROVED");
        if (q.getCustomerId() != null) pw.eq(Project::getCustomerId, q.getCustomerId());
        if (q.getManagerId() != null) pw.eq(Project::getSalesManagerId, q.getManagerId());
        inProject(pw, Project::getId, pids);
        List<Project> projects = projectMapper.selectList(pw);
        if (projects.isEmpty()) {
            vo.setRows(new ArrayList<>());
            vo.setTotal(0L);
            return vo;
        }

        // V72：客户名不再存 fin_project，按 customerId 批量联 fin_customer 翻译（免 N+1），与 ProjectServiceImpl.toVO 同范式
        Map<Long, String> custNames = new LinkedHashMap<>();
        {
            Set<Long> cids = projects.stream().map(Project::getCustomerId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            if (!cids.isEmpty()) {
                customerMapper.selectList(new LambdaQueryWrapper<Customer>().in(Customer::getId, cids))
                        .forEach(c -> custNames.put(c.getId(), c.getCustomerName()));
            }
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
                        .eq(Invoice::getApprovalStatus, "ISSUED")),
                Invoice::getProjectId, i -> signed(i.getInvoiceAmount(), i.getDocDirection()), null);
        Map<Long, BigDecimal> receiptMap = sumByProject(
                receiptMapper.selectList(new LambdaQueryWrapper<Receipt>()
                        .select(Receipt::getProjectId, Receipt::getReceiptAmount, Receipt::getDocDirection, Receipt::getReceiptType)
                        .eq(Receipt::getReceiptStatus, "RECEIVED")),
                Receipt::getProjectId, r -> receiptSigned(r.getReceiptAmount(), r.getDocDirection(), r.getReceiptType()), null);

        Map<Long, String> mgrNames = new LinkedHashMap<>();
        List<ProjectRankingRowVO> rows = new ArrayList<>();
        for (Project pr : projects) {
            ProjectRankingRowVO row = new ProjectRankingRowVO();
            row.setProjectId(pr.getId());
            row.setProjectNo(pr.getProjectNo());
            row.setProjectName(pr.getProjectName());
            row.setCustomerName(custNames.get(pr.getCustomerId()));
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
                .eq(Receipt::getReceiptStatus, "RECEIVED"))) {
            if ("RED".equals(rc.getDocDirection())) red = red.add(nz(rc.getReceiptAmount()));
            else blue = blue.add(nz(rc.getReceiptAmount()));
        }
        for (Invoice iv : invoiceMapper.selectList(new LambdaQueryWrapper<Invoice>()
                .select(Invoice::getInvoiceAmount, Invoice::getDocDirection)
                .eq(Invoice::getApprovalStatus, "ISSUED"))) {
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

    // ===================== L2 账龄（FIFO） =====================

    @Override
    public AgeingVO ageing(StatsQuery q) {
        AgeingVO v = new AgeingVO();
        v.setMethod("FIFO");
        List<Long> pids = resolveProjectIds(q);
        LocalDate[] p = periodDates(q);
        if (pids != null && pids.isEmpty()) {
            v.setSegments(emptySegments());
            return v;
        }

        // 1) 应收队列：有效应收单（蓝-红 effective），按 receivable_date 升序（FIFO 队头=最老）
        List<Receivable> queue = receivableMapper.selectList(new LambdaQueryWrapper<Receivable>()
                .select(Receivable::getId, Receivable::getProjectId, Receivable::getReceivableAmount,
                        Receivable::getDocDirection, Receivable::getReceivableDate))
                .stream()
                .filter(r -> pids == null || pids.contains(r.getProjectId()))
                .filter(r -> r.getReceivableDate() != null)
                .sorted(Comparator.comparing(Receivable::getReceivableDate).thenComparing(Receivable::getId))
                .collect(Collectors.toList());
        Set<Long> queueIds = queue.stream().map(Receivable::getId).collect(Collectors.toSet());

        // 2) 回款流水：NORMAL 核销，排除退款类收款（REFUND 反向核销），按 write_off_date 升序
        //    仅保留"挂在本主体应收"上的核销 → 主体级精确 FIFO（解决 L1 公司级近似）。
        List<WriteOff> wos = writeOffMapper.selectList(new LambdaQueryWrapper<WriteOff>()
                .select(WriteOff::getId, WriteOff::getReceivableId, WriteOff::getReceiptId,
                        WriteOff::getAmount, WriteOff::getWriteOffDate)
                .eq(WriteOff::getStatus, "NORMAL"));
        Set<Long> refundReceiptIds = new java.util.HashSet<>();
        Set<Long> usedReceiptIds = wos.stream().map(WriteOff::getReceiptId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (!usedReceiptIds.isEmpty()) {
            List<Receipt> receipts = receiptMapper.selectBatchIds(usedReceiptIds);
            for (Receipt rc : receipts) {
                if ("REFUND".equals(rc.getReceiptType())) refundReceiptIds.add(rc.getId());
            }
        }
        List<BigDecimal> cashflow = wos.stream()
                .filter(wo -> wo.getReceivableId() != null && queueIds.contains(wo.getReceivableId()))
                .filter(wo -> wo.getReceiptId() == null || !refundReceiptIds.contains(wo.getReceiptId()))
                .filter(wo -> wo.getWriteOffDate() != null)
                .sorted(Comparator.comparing(WriteOff::getWriteOffDate).thenComparing(WriteOff::getId))
                .map(wo -> nz(wo.getAmount()))
                .collect(Collectors.toList());

        // 3) FIFO 冲抵：回款依次吃掉最老应收的 effective
        BigDecimal[] consumed = new BigDecimal[queue.size()];
        Arrays.fill(consumed, BigDecimal.ZERO);
        int qi = 0;
        for (BigDecimal cf : cashflow) {
            BigDecimal remaining = cf;
            while (remaining.compareTo(BigDecimal.ZERO) > 0 && qi < queue.size()) {
                Receivable item = queue.get(qi);
                BigDecimal eff = signed(item.getReceivableAmount(), item.getDocDirection());
                if (eff.compareTo(BigDecimal.ZERO) <= 0) { qi++; continue; } // 红冲/空单不参与冲抵
                BigDecimal left = eff.subtract(consumed[qi]);
                if (left.compareTo(BigDecimal.ZERO) <= 0) { qi++; continue; }
                BigDecimal take = left.min(remaining);
                consumed[qi] = consumed[qi].add(take);
                remaining = remaining.subtract(take);
                if (consumed[qi].compareTo(eff) >= 0) qi++;
            }
        }

        // 4) 落账龄五段 + 逾期 / ADD 派生
        LocalDate today = LocalDate.now();
        BigDecimal[] seg = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal totalUnrec = BigDecimal.ZERO;
        BigDecimal overdueAmt = BigDecimal.ZERO;
        BigDecimal addWeight = BigDecimal.ZERO;
        BigDecimal addBase = BigDecimal.ZERO;
        int overdueCnt = 0;
        for (int i = 0; i < queue.size(); i++) {
            Receivable item = queue.get(i);
            BigDecimal eff = signed(item.getReceivableAmount(), item.getDocDirection());
            BigDecimal rem = eff.subtract(consumed[i]);
            if (rem.compareTo(BigDecimal.ZERO) <= 0) continue; // 已结清 / 红冲负余额
            totalUnrec = totalUnrec.add(rem);
            long age = ChronoUnit.DAYS.between(item.getReceivableDate(), today);
            int bucket;
            if (age <= 0) bucket = 0;
            else if (age <= 30) bucket = 1;
            else if (age <= 60) bucket = 2;
            else if (age <= 90) bucket = 3;
            else bucket = 4;
            seg[bucket] = seg[bucket].add(rem);
            if (age > 0) {
                overdueAmt = overdueAmt.add(rem);
                overdueCnt++;
                addWeight = addWeight.add(rem.multiply(BigDecimal.valueOf(age)));
                addBase = addBase.add(rem);
            }
        }
        String[] labels = {"未到期", "0-30天", "31-60天", "61-90天", "90天以上"};
        List<AgeingSegmentVO> segments = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AgeingSegmentVO s = new AgeingSegmentVO();
            s.setLabel(labels[i]);
            s.setAmount(seg[i]);
            s.setRatio(rate(seg[i], totalUnrec));
            segments.add(s);
        }
        v.setSegments(segments);
        v.setTotalUnreceived(totalUnrec);
        v.setOverdueAmount(overdueAmt);
        v.setOverdueCount(overdueCnt);
        v.setOverdueRate(rate(overdueAmt, totalUnrec));
        v.setAdd(addBase.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : addWeight.divide(addBase, 2, RoundingMode.HALF_UP));

        // DSO（D-03）/ 年化周转率（D-04）：期间窗口天数 × 余额 ÷ 期内应收发生额
        int days = (p[0] != null && p[1] != null) ? (int) (ChronoUnit.DAYS.between(p[0], p[1]) + 1) : 365;
        BigDecimal recvOccurred = BigDecimal.ZERO;
        {
            LambdaQueryWrapper<Receivable> w = new LambdaQueryWrapper<Receivable>()
                    .select(Receivable::getReceivableAmount, Receivable::getDocDirection);
            inProject(w, Receivable::getProjectId, pids);
            betweenDate(w, Receivable::getReceivableDate, p);
            for (Receivable r : receivableMapper.selectList(w)) {
                recvOccurred = recvOccurred.add(signed(r.getReceivableAmount(), r.getDocDirection()));
            }
        }
        BigDecimal dso = recvOccurred.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : totalUnrec.multiply(BigDecimal.valueOf(days)).divide(recvOccurred, 4, RoundingMode.HALF_UP);
        v.setDso(dso);
        v.setTurnover(dso.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(365).divide(dso, 2, RoundingMode.HALF_UP));
        return v;
    }

    // ===================== 工作台平台指标 =====================

    /**
     * 工作台横幅展示用的平台级指标（与经营看板解耦的独立端点）：
     * - userCount：活跃用户数（status='ENABLED'，MP 自动追加逻辑删除过滤）；
     * - todayLoginCount：今日（本地时区 00:00 起）成功登录次数，作为「访问指标」。
     * 只读，不写库。
     */
    @Override
    public PlatformStatsVO platformStats() {
        PlatformStatsVO v = new PlatformStatsVO();
        v.setUserCount(userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getStatus, "ENABLED")));
        LocalDate today = LocalDate.now();
        v.setTodayLoginCount(loginLogMapper.selectCount(new LambdaQueryWrapper<LoginLog>()
                .eq(LoginLog::getLoginStatus, "SUCCESS")
                .ge(LoginLog::getLoginAt, today.atStartOfDay())));
        v.setHistoryVisitCount(loginLogMapper.selectCount(new LambdaQueryWrapper<LoginLog>()
                .eq(LoginLog::getLoginStatus, "SUCCESS")));
        return v;
    }

    /** 空账龄五段（无匹配主体时返回） */
    private List<AgeingSegmentVO> emptySegments() {
        String[] labels = {"未到期", "0-30天", "31-60天", "61-90天", "90天以上"};
        List<AgeingSegmentVO> segs = new ArrayList<>();
        for (String l : labels) {
            AgeingSegmentVO s = new AgeingSegmentVO();
            s.setLabel(l);
            s.setAmount(BigDecimal.ZERO);
            s.setRatio(BigDecimal.ZERO);
            segs.add(s);
        }
        return segs;
    }

    // ===================== 辅助 =====================

    /**
     * 客户总数：pids 为 null（未筛选项目）时取全量 ENABLED 客户；
     * 指定项目时收敛为「这些项目关联的去重启用客户数」（筛选 PJ-xxx 时不再显示全公司客户数）。
     */
    private long customerEnabledCount(List<Long> pids) {
        if (pids == null) {
            return customerMapper.selectCount(new LambdaQueryWrapper<Customer>().eq(Customer::getStatus, "ENABLED"));
        }
        if (pids.isEmpty()) {
            return 0L;
        }
        List<Long> cids = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .select(Project::getCustomerId).in(Project::getId, pids)).stream()
                .map(Project::getCustomerId).filter(Objects::nonNull).distinct()
                .collect(Collectors.toList());
        if (cids.isEmpty()) {
            return 0L;
        }
        return customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getStatus, "ENABLED").in(Customer::getId, cids));
    }

    /**
     * 货物总数：pids 为 null（未筛选项目）时取全量 ENABLED 货物主数据（与历史口径一致）；
     * 指定项目时收敛为「这些项目发货涉及的商品(SKU)去重计数」（fin_item via fin_shipment_line），
     * 因 fin_goods 与 fin_item 为两张表、发货行只挂 item_id，故按实际发货 SKU 计本项目货物数。
     */
    private long goodsEnabledCount(List<Long> pids) {
        if (pids == null) {
            return goodsMapper.selectCount(new LambdaQueryWrapper<Goods>().eq(Goods::getStatus, "ENABLED"));
        }
        if (pids.isEmpty()) {
            return 0L;
        }
        List<Long> shipmentIds = shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>()
                        .select(Shipment::getId).in(Shipment::getProjectId, pids)).stream()
                .map(Shipment::getId).collect(Collectors.toList());
        if (shipmentIds.isEmpty()) {
            return 0L;
        }
        return shipmentLineMapper.selectList(new LambdaQueryWrapper<ShipmentLine>()
                        .select(ShipmentLine::getItemId).in(ShipmentLine::getShipmentId, shipmentIds)).stream()
                .map(ShipmentLine::getItemId).filter(Objects::nonNull).distinct().count();
    }
}
