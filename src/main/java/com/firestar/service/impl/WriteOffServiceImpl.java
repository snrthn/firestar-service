package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.dto.AutoMatchResultVO;
import com.firestar.dto.DifferencePoolVO;
import com.firestar.dto.MatchedPairVO;
import com.firestar.dto.PoolItemVO;
import com.firestar.dto.ReverseRequest;
import com.firestar.dto.WriteOffLedgerVO;
import com.firestar.entity.Invoice;
import com.firestar.entity.InvoiceAllocation;
import com.firestar.entity.Project;
import com.firestar.entity.Receipt;
import com.firestar.entity.Receivable;
import com.firestar.entity.User;
import com.firestar.entity.WriteOff;
import com.firestar.mapper.InvoiceAllocationMapper;
import com.firestar.mapper.InvoiceMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceiptMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.WriteOffMapper;
import com.firestar.service.WriteOffService;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 核销台账实现（2026-10-25 阶段一）。
 *
 * <p>纪律：本模块只做「查询编排 + 落核销行 + 滚数字」，滚数字全部复制
 * ReceiptServiceImpl / InvoiceServiceImpl 的同构私有方法（rollReceivable 等），
 * 不抽取公共类——与现有代码风格一致（两个ServiceImpl 本就各自持有一份）。</p>
 *
 * <p>金额纪律：一律 BigDecimal，null 当 0（nz()），用 add/subtract 不用 +/-。</p>
 */
@Slf4j
@Service
public class WriteOffServiceImpl implements WriteOffService {

    /** 台账类型：回款核销（fin_write_off） */
    public static final String LEDGER_RECEIPT = "RECEIPT";

    /** 台账类型：开票核销（fin_invoice_allocation） */
    public static final String LEDGER_INVOICE = "INVOICE";

    private static final DateTimeFormatter LEDGER_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WriteOffMapper writeOffMapper;
    private final InvoiceAllocationMapper invoiceAllocationMapper;
    private final ReceiptMapper receiptMapper;
    private final ReceivableMapper receivableMapper;
    private final InvoiceMapper invoiceMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;

    @Autowired
    public WriteOffServiceImpl(WriteOffMapper writeOffMapper,
                               InvoiceAllocationMapper invoiceAllocationMapper,
                               ReceiptMapper receiptMapper,
                               ReceivableMapper receivableMapper,
                               InvoiceMapper invoiceMapper,
                               ProjectMapper projectMapper,
                               UserMapper userMapper) {
        this.writeOffMapper = writeOffMapper;
        this.invoiceAllocationMapper = invoiceAllocationMapper;
        this.receiptMapper = receiptMapper;
        this.receivableMapper = receivableMapper;
        this.invoiceMapper = invoiceMapper;
        this.projectMapper = projectMapper;
        this.userMapper = userMapper;
    }

    // ===================== 台账查询 =====================

    @Override
    public IPage<WriteOffLedgerVO> pageLedger(Integer pageNum, Integer pageSize, Long projectId,
                                              String ledgerType, String status, String keyword,
                                              LocalDate dateFrom, LocalDate dateTo) {
        int pn = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int ps = (pageSize == null || pageSize < 1) ? 20 : pageSize;
        if (ledgerType != null && !LEDGER_RECEIPT.equals(ledgerType) && !LEDGER_INVOICE.equals(ledgerType)) {
            throw new IllegalArgumentException("ledgerType 仅支持 RECEIPT/INVOICE，当前：" + ledgerType);
        }
        // ① 项目收窄：两张核销明细表都无 project_id，先收敛应收单 ID 集合（空 = 直接空页）
        Set<Long> recvIds = null;
        if (projectId != null) {
            recvIds = receivableMapper.selectList(new LambdaQueryWrapper<Receivable>()
                            .eq(Receivable::getProjectId, projectId)
                            .select(Receivable::getId))
                    .stream().map(Receivable::getId).collect(Collectors.toSet());
            if (recvIds.isEmpty()) {
                return emptyPage(pn, ps);
            }
        }
        // ② 双表分别查（内存合并，同 ProjectServiceImpl.amountsOf 范式；
        //    跨表 UNION 需手写 XML，破 V1「零手写 SQL」纪律，台账数据量下不值得）
        List<WriteOffLedgerVO> all = new ArrayList<>();
        if (ledgerType == null || LEDGER_RECEIPT.equals(ledgerType)) {
            LambdaQueryWrapper<WriteOff> q = new LambdaQueryWrapper<>();
            if (recvIds != null) {
                q.in(WriteOff::getReceivableId, recvIds);
            }
            if (status != null && !status.isBlank()) {
                q.eq(WriteOff::getStatus, status);
            }
            if (keyword != null && !keyword.isBlank()) {
                q.like(WriteOff::getWriteOffNo, keyword);
            }
            if (dateFrom != null) {
                q.ge(WriteOff::getWriteOffDate, dateFrom);
            }
            if (dateTo != null) {
                q.le(WriteOff::getWriteOffDate, dateTo);
            }
            for (WriteOff w : writeOffMapper.selectList(q)) {
                all.add(toReceiptLedgerVO(w));
            }
        }
        if (ledgerType == null || LEDGER_INVOICE.equals(ledgerType)) {
            LambdaQueryWrapper<InvoiceAllocation> q = new LambdaQueryWrapper<>();
            if (recvIds != null) {
                q.in(InvoiceAllocation::getReceivableId, recvIds);
            }
            if (status != null && !status.isBlank()) {
                q.eq(InvoiceAllocation::getStatus, status);
            }
            if (keyword != null && !keyword.isBlank()) {
                q.like(InvoiceAllocation::getAllocationNo, keyword);
            }
            // 该表无 write_off_date 列，日期过滤回落 created_at（左闭右开）
            if (dateFrom != null) {
                q.ge(InvoiceAllocation::getCreatedAt, dateFrom.atStartOfDay());
            }
            if (dateTo != null) {
                q.le(InvoiceAllocation::getCreatedAt, dateTo.plusDays(1).atStartOfDay());
            }
            for (InvoiceAllocation a : invoiceAllocationMapper.selectList(q)) {
                all.add(toInvoiceLedgerVO(a));
            }
        }
        // ③ 批量翻译：应收单号 / 主单号 / 项目号 / 创建人
        fillNames(all);
        // ④ 合并排序（createdAt 倒序）+ 内存分页
        all.sort(Comparator.comparing(WriteOffLedgerVO::getCreatedAt).reversed());
        int from = Math.min((pn - 1) * ps, all.size());
        int to = Math.min(from + ps, all.size());
        Page<WriteOffLedgerVO> page = new Page<>(pn, ps, all.size());
        page.setRecords(all.subList(from, to));
        return page;
    }

    // ===================== 差异池 =====================

    @Override
    public List<DifferencePoolVO> differencePool(Long projectId) {
        // 应收单侧：未回款余额 > 0（日期早优先，与 autoMatch 排序一致）
        LambdaQueryWrapper<Receivable> rq = new LambdaQueryWrapper<Receivable>()
                .gt(Receivable::getUnreceivedAmount, BigDecimal.ZERO)
                .orderByAsc(Receivable::getReceivableDate)
                .orderByAsc(Receivable::getId);
        if (projectId != null) {
            rq.eq(Receivable::getProjectId, projectId);
        }
        List<Receivable> receivables = receivableMapper.selectList(rq);
        // 收款单侧：已收款 + 有未核销余额（红冲单内存过滤——docDirection 老数据可能 NULL，
        // .ne 会把 NULL 行也误排除，与 amountsOf 的 RED 判断保持内存过滤）
        LambdaQueryWrapper<Receipt> pq = new LambdaQueryWrapper<Receipt>()
                .eq(Receipt::getReceiptStatus, "RECEIVED")
                .gt(Receipt::getUnwrittenOffAmount, BigDecimal.ZERO)
                .orderByAsc(Receipt::getReceiptDate)
                .orderByAsc(Receipt::getId);
        if (projectId != null) {
            pq.eq(Receipt::getProjectId, projectId);
        }
        List<Receipt> receipts = receiptMapper.selectList(pq).stream()
                .filter(r -> !"RED".equals(r.getDocDirection()))
                .collect(Collectors.toList());
        // 项目号批量翻译
        Set<Long> projectIds = new java.util.HashSet<>();
        receivables.forEach(r -> {
            if (r.getProjectId() != null) {
                projectIds.add(r.getProjectId());
            }
        });
        receipts.forEach(r -> {
            if (r.getProjectId() != null) {
                projectIds.add(r.getProjectId());
            }
        });
        Map<Long, String> projectNos = projectNosOf(projectIds);
        // 按项目分组（项目 ID 升序，输出稳定）
        Map<Long, DifferencePoolVO> byProject = new LinkedHashMap<>();
        for (Receivable v : receivables) {
            if (v.getProjectId() == null) {
                continue;
            }
            DifferencePoolVO pool = byProject.computeIfAbsent(v.getProjectId(), k -> {
                DifferencePoolVO p = new DifferencePoolVO();
                p.setProjectId(k);
                p.setProjectNo(projectNos.get(k));
                p.setReceipts(new ArrayList<>());
                p.setReceivables(new ArrayList<>());
                return p;
            });
            pool.getReceivables().add(poolItem(v.getId(), v.getReceivableNo(),
                    v.getReceivableAmount(), v.getUnreceivedAmount(), v.getReceivableDate()));
        }
        for (Receipt r : receipts) {
            if (r.getProjectId() == null) {
                continue;
            }
            DifferencePoolVO pool = byProject.computeIfAbsent(r.getProjectId(), k -> {
                DifferencePoolVO p = new DifferencePoolVO();
                p.setProjectId(k);
                p.setProjectNo(projectNos.get(k));
                p.setReceipts(new ArrayList<>());
                p.setReceivables(new ArrayList<>());
                return p;
            });
            pool.getReceipts().add(poolItem(r.getId(), r.getReceiptNo(),
                    r.getReceiptAmount(), r.getUnwrittenOffAmount(), r.getReceiptDate()));
        }
        List<DifferencePoolVO> result = new ArrayList<>(byProject.values());
        result.sort(Comparator.comparing(DifferencePoolVO::getProjectId));
        return result;
    }

    // ===================== 自动核销 =====================

    @Override
    @Transactional
    public AutoMatchResultVO autoMatch(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("自动核销必须指定项目（禁止跨项目错配）");
        }
        // 收款单：已收款 + 非红冲 + 有未核销余额，日期早优先
        List<Receipt> receipts = receiptMapper.selectList(new LambdaQueryWrapper<Receipt>()
                        .eq(Receipt::getProjectId, projectId)
                        .eq(Receipt::getReceiptStatus, "RECEIVED")
                        .gt(Receipt::getUnwrittenOffAmount, BigDecimal.ZERO)
                        .orderByAsc(Receipt::getReceiptDate)
                        .orderByAsc(Receipt::getId))
                .stream().filter(r -> !"RED".equals(r.getDocDirection()))
                .collect(Collectors.toList());
        // 应收单：有未回款余额，日期早优先
        List<Receivable> receivables = receivableMapper.selectList(new LambdaQueryWrapper<Receivable>()
                .eq(Receivable::getProjectId, projectId)
                .gt(Receivable::getUnreceivedAmount, BigDecimal.ZERO)
                .orderByAsc(Receivable::getReceivableDate)
                .orderByAsc(Receivable::getId));
        AutoMatchResultVO result = new AutoMatchResultVO();
        result.setMatchCount(0);
        result.setTotalAmount(BigDecimal.ZERO);
        result.setPairs(new ArrayList<>());
        if (receipts.isEmpty() || receivables.isEmpty()) {
            throw new IllegalArgumentException("该项目没有可配对的未核销收款单或未回款应收单");
        }
        // 内存剩余余额（避免每对都回查库）
        Map<Long, BigDecimal> recvLeft = new HashMap<>();
        for (Receivable v : receivables) {
            recvLeft.put(v.getId(), nz(v.getUnreceivedAmount()));
        }
        Map<Long, String> receiptNos = new HashMap<>();
        Map<Long, String> receivableNos = new HashMap<>();
        // 贪心：逐收款单顺序吃应收单余额
        for (Receipt r : receipts) {
            BigDecimal left = nz(r.getUnwrittenOffAmount());
            for (Receivable v : receivables) {
                if (left.signum() <= 0) {
                    break;
                }
                BigDecimal vLeft = recvLeft.get(v.getId());
                if (vLeft == null || vLeft.signum() <= 0) {
                    continue;
                }
                BigDecimal m = left.min(vLeft);
                if (m.signum() <= 0) {
                    continue;
                }
                // 落行（method=AUTO，字典 writeoff_method 已种）
                WriteOff w = new WriteOff();
                w.setWriteOffNo(generateWriteOffNo());
                w.setReceiptId(r.getId());
                w.setReceivableId(v.getId());
                w.setAmount(m);
                w.setStatus("NORMAL");
                w.setWriteOffMethod("AUTO");
                w.setWriteOffDate(LocalDate.now());
                w.setOperatorId(currentUserId());
                writeOffMapper.insert(w);
                // 滚应收单 received/unreceived + 推进内存余额
                rollReceivableReceived(v.getId(), m, true);
                left = left.subtract(m);
                recvLeft.put(v.getId(), vLeft.subtract(m));
                MatchedPairVO pair = new MatchedPairVO();
                pair.setWriteOffNo(w.getWriteOffNo());
                pair.setReceiptId(r.getId());
                pair.setReceiptNo(receiptNos.computeIfAbsent(r.getId(), k -> r.getReceiptNo()));
                pair.setReceivableId(v.getId());
                pair.setReceivableNo(receivableNos.computeIfAbsent(v.getId(), k -> v.getReceivableNo()));
                pair.setAmount(m);
                result.getPairs().add(pair);
                result.setMatchCount(result.getMatchCount() + 1);
                result.setTotalAmount(result.getTotalAmount().add(m));
                log.info("[writeoff] 自动核销配对 writeOffNo={} receiptId={} receivableId={} amount={}",
                        w.getWriteOffNo(), r.getId(), v.getId(), m);
            }
            // 本单核销口径重算（written/unwritten）
            recalcReceiptWritten(r.getId());
        }
        if (result.getMatchCount() == 0) {
            throw new IllegalArgumentException("没有可自动配对的组合（检查余额与日期）");
        }
        return result;
    }

    // ===================== 反核销 =====================

    @Override
    @Transactional
    public void reverse(ReverseRequest req) {
        if (req == null || req.getLedgerId() == null) {
            throw new IllegalArgumentException("ledgerId 不能为空");
        }
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new IllegalArgumentException("反核销原因不能为空");
        }
        if (LEDGER_RECEIPT.equals(req.getLedgerType())) {
            reverseReceiptLedger(req);
        } else if (LEDGER_INVOICE.equals(req.getLedgerType())) {
            reverseInvoiceLedger(req);
        } else {
            throw new IllegalArgumentException("ledgerType 仅支持 RECEIPT/INVOICE，当前：" + req.getLedgerType());
        }
    }

    private void reverseReceiptLedger(ReverseRequest req) {
        WriteOff w = writeOffMapper.selectById(req.getLedgerId());
        if (w == null) {
            throw new IllegalArgumentException("核销记录不存在：" + req.getLedgerId());
        }
        if (!"NORMAL".equals(w.getStatus())) {
            throw new IllegalArgumentException("仅 NORMAL 状态可反核销，当前状态：" + w.getStatus());
        }
        // 原主单为红冲单时，核销生效做的是「减已回款」，反核销即加回（方向取反）
        Receipt r = receiptMapper.selectById(w.getReceiptId());
        boolean red = r != null && "RED".equals(r.getDocDirection());
        w.setStatus("REVERSED");
        w.setOriginalWriteOffId(w.getId());
        Map<String, Object> ext = w.getExt() == null ? new HashMap<>() : new HashMap<>(w.getExt());
        ext.put("reverseReason", req.getReason());
        ext.put("reversedBy", currentUsername());
        ext.put("reversedAt", LocalDateTime.now().toString());
        w.setExt(ext);
        w.setOperatorId(currentUserId());
        writeOffMapper.updateById(w);
        rollReceivableReceived(w.getReceivableId(), w.getAmount(), red);
        recalcReceiptWritten(w.getReceiptId());
        log.info("[writeoff] 反核销 receiptLedger id={} receiptId={} receivableId={} amount={} red={}",
                w.getId(), w.getReceiptId(), w.getReceivableId(), w.getAmount(), red);
    }

    private void reverseInvoiceLedger(ReverseRequest req) {
        InvoiceAllocation a = invoiceAllocationMapper.selectById(req.getLedgerId());
        if (a == null) {
            throw new IllegalArgumentException("核销记录不存在：" + req.getLedgerId());
        }
        if (!"NORMAL".equals(a.getStatus())) {
            throw new IllegalArgumentException("仅 NORMAL 状态可反核销，当前状态：" + a.getStatus());
        }
        Invoice iv = invoiceMapper.selectById(a.getInvoiceId());
        boolean red = iv != null && "RED".equals(iv.getDocDirection());
        a.setStatus("REVERSED");
        a.setOriginalAllocationId(a.getId());
        Map<String, Object> ext = a.getExt() == null ? new HashMap<>() : new HashMap<>(a.getExt());
        ext.put("reverseReason", req.getReason());
        ext.put("reversedBy", currentUsername());
        ext.put("reversedAt", LocalDateTime.now().toString());
        a.setExt(ext);
        invoiceAllocationMapper.updateById(a);
        // 只滚应收单已开票口径；fin_invoice 自身无 written_off 维护点
        // （completeTask 同口径不写，V1 列沿用），重算无意义故不做
        rollReceivableInvoiced(a.getReceivableId(), a.getAmount(), red);
        log.info("[writeoff] 反核销 invoiceLedger id={} invoiceId={} receivableId={} amount={} red={}",
                a.getId(), a.getInvoiceId(), a.getReceivableId(), a.getAmount(), red);
    }

    // ===================== 私有：装配 =====================

    private WriteOffLedgerVO toReceiptLedgerVO(WriteOff w) {
        WriteOffLedgerVO vo = new WriteOffLedgerVO();
        vo.setId(w.getId());
        vo.setLedgerType(LEDGER_RECEIPT);
        vo.setLedgerNo(w.getWriteOffNo());
        vo.setBizId(w.getReceiptId());
        vo.setReceivableId(w.getReceivableId());
        vo.setAmount(w.getAmount());
        vo.setStatus(w.getStatus());
        vo.setMethod(w.getWriteOffMethod());
        vo.setLedgerDate(w.getWriteOffDate());
        vo.setCreatedAt(w.getCreatedAt());
        vo.setCreatedBy(w.getCreatedBy());
        vo.setOriginalLedgerId(w.getOriginalWriteOffId());
        vo.setReverseReason(reverseReasonOf(w.getExt()));
        return vo;
    }

    private WriteOffLedgerVO toInvoiceLedgerVO(InvoiceAllocation a) {
        WriteOffLedgerVO vo = new WriteOffLedgerVO();
        vo.setId(a.getId());
        vo.setLedgerType(LEDGER_INVOICE);
        vo.setLedgerNo(a.getAllocationNo());
        vo.setBizId(a.getInvoiceId());
        vo.setReceivableId(a.getReceivableId());
        vo.setAmount(a.getAmount());
        vo.setStatus(a.getStatus());
        // 该表无 method 列：开票核销均由分配明细产生，口径恒 MANUAL
        vo.setMethod("MANUAL");
        vo.setLedgerDate(a.getCreatedAt() == null ? null : a.getCreatedAt().toLocalDate());
        vo.setCreatedAt(a.getCreatedAt());
        vo.setCreatedBy(a.getCreatedBy());
        vo.setOriginalLedgerId(a.getOriginalAllocationId());
        vo.setReverseReason(reverseReasonOf(a.getExt()));
        return vo;
    }

    /** 批量翻译：应收单号+项目 / 主单号 / 项目号 / 创建人名（四次批量查，禁 N+1） */
    private void fillNames(List<WriteOffLedgerVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, Receivable> recvMap = receivableMapper.selectList(new LambdaQueryWrapper<Receivable>()
                        .in(Receivable::getId, rows.stream()
                                .map(WriteOffLedgerVO::getReceivableId).collect(Collectors.toSet())))
                .stream().collect(Collectors.toMap(Receivable::getId, r -> r, (a, b) -> a));
        Set<Long> receiptIds = rows.stream()
                .filter(r -> LEDGER_RECEIPT.equals(r.getLedgerType()) && r.getBizId() != null)
                .map(WriteOffLedgerVO::getBizId).collect(Collectors.toSet());
        Set<Long> invoiceIds = rows.stream()
                .filter(r -> LEDGER_INVOICE.equals(r.getLedgerType()) && r.getBizId() != null)
                .map(WriteOffLedgerVO::getBizId).collect(Collectors.toSet());
        Map<Long, String> receiptNos = receiptIds.isEmpty() ? Map.of()
                : receiptMapper.selectList(new LambdaQueryWrapper<Receipt>()
                                .in(Receipt::getId, receiptIds))
                        .stream().collect(Collectors.toMap(Receipt::getId, Receipt::getReceiptNo, (a, b) -> a));
        Map<Long, String> invoiceNos = invoiceIds.isEmpty() ? Map.of()
                : invoiceMapper.selectList(new LambdaQueryWrapper<Invoice>()
                                .in(Invoice::getId, invoiceIds))
                        .stream().collect(Collectors.toMap(Invoice::getId, Invoice::getInvoiceNo, (a, b) -> a));
        Set<Long> projectIds = recvMap.values().stream()
                .map(Receivable::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> projectNos = projectNosOf(projectIds);
        Set<Long> creatorIds = rows.stream()
                .map(WriteOffLedgerVO::getCreatedBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> userNames = creatorIds.isEmpty() ? Map.of()
                : userMapper.selectList(new LambdaQueryWrapper<User>()
                                .in(User::getId, creatorIds))
                        .stream().collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        for (WriteOffLedgerVO vo : rows) {
            Receivable rv = recvMap.get(vo.getReceivableId());
            if (rv != null) {
                vo.setReceivableNo(rv.getReceivableNo());
                vo.setProjectId(rv.getProjectId());
                vo.setProjectNo(projectNos.get(rv.getProjectId()));
            }
            vo.setBizNo(LEDGER_RECEIPT.equals(vo.getLedgerType())
                    ? receiptNos.get(vo.getBizId()) : invoiceNos.get(vo.getBizId()));
            vo.setCreatedByName(vo.getCreatedBy() == null ? null : userNames.get(vo.getCreatedBy()));
        }
    }

    private String reverseReasonOf(Map<String, Object> ext) {
        if (ext == null) {
            return null;
        }
        Object r = ext.get("reverseReason");
        return r == null ? null : r.toString();
    }

    private PoolItemVO poolItem(Long id, String no, BigDecimal amount,
                                BigDecimal unmatched, LocalDate docDate) {
        PoolItemVO item = new PoolItemVO();
        item.setId(id);
        item.setNo(no);
        item.setAmount(amount);
        item.setUnmatchedAmount(unmatched);
        item.setDocDate(docDate);
        return item;
    }

    private Map<Long, String> projectNosOf(Set<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .in(Project::getId, projectIds))
                .stream().collect(Collectors.toMap(Project::getId, Project::getProjectNo, (a, b) -> a));
    }

    private IPage<WriteOffLedgerVO> emptyPage(int pn, int ps) {
        Page<WriteOffLedgerVO> page = new Page<>(pn, ps, 0);
        page.setRecords(List.of());
        return page;
    }

    // ===================== 私有：账务（同构复制 Receipt/InvoiceServiceImpl） =====================

    /** 滚（add）或回滚（!add）应收单回款口径：received/unreceived 成对增减 */
    private void rollReceivableReceived(Long receivableId, BigDecimal amount, boolean add) {
        Receivable rv = receivableMapper.selectById(receivableId);
        if (rv == null) {
            return;
        }
        BigDecimal received = nz(rv.getReceivedAmount());
        BigDecimal unreceived = nz(rv.getUnreceivedAmount());
        rv.setReceivedAmount(add ? received.add(amount) : received.subtract(amount));
        rv.setUnreceivedAmount(add ? unreceived.subtract(amount) : unreceived.add(amount));
        receivableMapper.updateById(rv);
    }

    /** 滚（add）或回滚（!add）应收单开票口径：invoiced/uninvoiced 成对增减 */
    private void rollReceivableInvoiced(Long receivableId, BigDecimal amount, boolean add) {
        Receivable rv = receivableMapper.selectById(receivableId);
        if (rv == null) {
            return;
        }
        BigDecimal invoiced = nz(rv.getInvoicedAmount());
        BigDecimal uninvoiced = nz(rv.getUninvoicedAmount());
        rv.setInvoicedAmount(add ? invoiced.add(amount) : invoiced.subtract(amount));
        rv.setUninvoicedAmount(add ? uninvoiced.subtract(amount) : uninvoiced.add(amount));
        receivableMapper.updateById(rv);
    }

    /** 重算收款单已核销口径：written = Σ NORMAL 行，unwritten = 收款金额 - written */
    private void recalcReceiptWritten(Long receiptId) {
        Receipt r = receiptMapper.selectById(receiptId);
        if (r == null) {
            return;
        }
        List<WriteOff> rows = writeOffMapper.selectList(new LambdaQueryWrapper<WriteOff>()
                .eq(WriteOff::getReceiptId, receiptId)
                .eq(WriteOff::getStatus, "NORMAL"));
        BigDecimal written = rows.stream()
                .map(WriteOff::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        r.setWrittenOffAmount(written);
        r.setUnwrittenOffAmount(nz(r.getReceiptAmount()).subtract(written));
        receiptMapper.updateById(r);
    }

    /** 核销单号：WO-yyyyMMdd-NNN（同 ReceiptServiceImpl.generateWriteOffNo 范式） */
    private String generateWriteOffNo() {
        String date = LocalDateTime.now().format(LEDGER_NO_DATE_FMT);
        Long count = writeOffMapper.selectCount(new LambdaQueryWrapper<WriteOff>()
                .likeRight(WriteOff::getWriteOffNo, "WO-" + date + "-"));
        return String.format("WO-%s-%03d", date, (count == null ? 0 : count) + 1);
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
