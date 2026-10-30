package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.common.BatchLoader;
import com.firestar.common.DataScopeHelper;
import com.firestar.common.SecurityContextUtils;
import com.firestar.dto.ReceivableVO;
import com.firestar.entity.DictData;
import com.firestar.entity.Project;
import com.firestar.entity.Receivable;
import com.firestar.entity.Shipment;
import com.firestar.entity.ShipmentLine;
import com.firestar.mapper.DictDataMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.ShipmentLineMapper;
import com.firestar.mapper.ShipmentMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.ReceivableService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 应收单生成实现：客户已收货 → 蓝字应收单（doc_direction=BLUE, source_type=FROM_SHIPMENT）。
 *
 * <p>tenant_id / version 不手动 set（BaseEntity 约定：由插件/拦截器管，业务代码不碰）。</p>
 *
 * <p>V59 汇总列维护：本服务在「生成应收单」时按 delta 累加 fin_project.receivable_amount
 * （金额纪律：null 当 0，add，不 +=）。部分收货续收场景用
 * 新金额 - 旧金额 作为 delta，避免重复累加。</p>
 */
@Slf4j
@Service
public class ReceivableServiceImpl implements ReceivableService {

    /**
     * 应收单号规则：YS-{yyyyMMdd}-{三位序号}（当日自增，与发货批次编号同范式）。
     *
     * <p>⚠️ 必须「先算后插」：fin_receivable.receivable_no 是 NOT NULL 无默认列
     * （V1 基线），曾用「先 insert 占 id 再回写」范式（对齐 project_no 的可空列），
     * 首次 insert 即撞 Field 'receivable_no' doesn't have a default value（2026-10-23 实锤）。</p>
     */
    private static final DateTimeFormatter RECEIVABLE_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String DICT_RECEIVABLE_STATUS = "receivable_status";
    private static final String DICT_DOC_DIRECTION = "doc_direction";
    private static final String DICT_RECEIVABLE_SOURCE = "receivable_source";

    private final ReceivableMapper receivableMapper;
    private final ShipmentMapper shipmentMapper;
    private final ShipmentLineMapper shipmentLineMapper;
    private final ProjectMapper projectMapper;
    private final DictDataMapper dictDataMapper;
    private final UserMapper userMapper;

    @Autowired
    public ReceivableServiceImpl(ReceivableMapper receivableMapper,
                                ShipmentMapper shipmentMapper,
                                ShipmentLineMapper shipmentLineMapper,
                                ProjectMapper projectMapper,
                                DictDataMapper dictDataMapper,
                                UserMapper userMapper) {
        this.receivableMapper = receivableMapper;
        this.shipmentMapper = shipmentMapper;
        this.shipmentLineMapper = shipmentLineMapper;
        this.projectMapper = projectMapper;
        this.dictDataMapper = dictDataMapper;
        this.userMapper = userMapper;
    }

    @Override
    public Long createFromShipment(Long shipmentId, Long operatorId) {
        // 幂等：同发货单已生成过应收单则「金额重算后复用」（2026-10-24 虎哥：部分收货可续收，
        // 第二次收货必须把新增已收金额补进同一张应收单——既不能重复建单，也不能金额停在首收值）
        Receivable exist = receivableMapper.selectOne(new LambdaQueryWrapper<Receivable>()
                .eq(Receivable::getShipmentId, shipmentId));
        if (exist != null) {
            BigDecimal amount = cumulativeReceivedAmount(shipmentId);
            // delta = 新金额 - 旧金额（避免 V59 汇总列重复累加）
            BigDecimal old = exist.getReceivableAmount() == null ? BigDecimal.ZERO : exist.getReceivableAmount();
            BigDecimal delta = amount.subtract(old);
            exist.setReceivableAmount(amount);
            // 已核销/已开票保持不动（可能已有后续核销/开票动作），未核销/未开票 = 应收 - 对应已发生额
            BigDecimal writtenOff = exist.getWrittenOffAmount() == null ? BigDecimal.ZERO : exist.getWrittenOffAmount();
            BigDecimal invoiced = exist.getInvoicedAmount() == null ? BigDecimal.ZERO : exist.getInvoicedAmount();
            exist.setUnwrittenOffAmount(amount.subtract(writtenOff));
            exist.setUninvoicedAmount(amount.subtract(invoiced));
            exist.setUpdatedBy(operatorId);
            receivableMapper.updateById(exist);
            // V59：累加项目应收汇总列（delta 可能为正/负，部分收货续收为正）
            addToProjectReceivable(exist.getProjectId(), delta);
            log.info("[receivable] 部分收货续收，应收单金额重算 id={} no={} amount={}",
                    exist.getId(), exist.getReceivableNo(), amount);
            return exist.getId();
        }
        Shipment shipment = shipmentMapper.selectById(shipmentId);
        if (shipment == null) {
            throw new IllegalArgumentException("发货单不存在：" + shipmentId);
        }
        // 金额 = Σ 已收数量 × 单价（决策#2：金额按 receivedQty 计算，收货确认才确认金额）
        BigDecimal amount = cumulativeReceivedAmount(shipmentId);

        Receivable r = new Receivable();
        r.setProjectId(shipment.getProjectId());
        r.setShipmentId(shipmentId);
        r.setDocDirection("BLUE");         // 蓝字（V2 字典 doc_direction=BLUE）
        // V85：用户级隔离归属字段（申请人 OR 发货人可见）
        r.setApplicantId(shipment.getApplicantId());
        r.setShipperId(shipment.getShippedById());
        r.setSourceType("FROM_SHIPMENT");  // 发货生成（V2 字典 receivable_source=FROM_SHIPMENT）
        r.setReceivableAmount(amount);
        r.setWrittenOffAmount(BigDecimal.ZERO);
        r.setUnwrittenOffAmount(amount);
        r.setInvoicedAmount(BigDecimal.ZERO);
        r.setUninvoicedAmount(amount);
        r.setReceivedAmount(BigDecimal.ZERO);   // V65 回款侧核销列，与开票侧对称初始化
        r.setUnreceivedAmount(amount);
        r.setReceivableDate(LocalDate.now());
        r.setStatus("CONFIRMED");          // 客户已收货 = 金额已确认（V2 字典 receivable_status=CONFIRMED）
        r.setReceivableNo(generateReceivableNo()); // NOT NULL 无默认列：插入前必须算好
        r.setCreatedBy(operatorId);
        receivableMapper.insert(r);

        // V59：累加项目应收汇总列
        addToProjectReceivable(r.getProjectId(), amount);

        log.info("[receivable] 由发货单生成应收单 id={} no={} amount={}", r.getId(), r.getReceivableNo(), amount);
        return r.getId();
    }

    @Override
    public ReceivableVO detail(Long id) {
        Receivable r = receivableMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("应收单不存在：" + id);
        }
        Map<String, DictData> statusDict = dictMap(DICT_RECEIVABLE_STATUS);
        Map<String, DictData> docDict = dictMap(DICT_DOC_DIRECTION);
        Map<String, DictData> sourceDict = dictMap(DICT_RECEIVABLE_SOURCE);
        Map<Long, Project> projects =
                BatchLoader.projects(projectMapper, List.of(r), Receivable::getProjectId);
        return toVO(r, statusDict, docDict, sourceDict, projects);
    }

    @Override
    public IPage<ReceivableVO> page(Integer pageNum, Integer pageSize, Long projectId, String status,
                                    String receivableNo, String projectName, String sourceType,
                                    LocalDate dateFrom, LocalDate dateTo) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 500L);
        String statusEq = status == null || status.isBlank() ? null : status.trim();
        String noKw = receivableNo == null || receivableNo.isBlank() ? null : receivableNo.trim();
        String nameKw = projectName == null || projectName.isBlank() ? null : projectName.trim();
        String sourceEq = sourceType == null || sourceType.isBlank() ? null : sourceType.trim();

        // 项目名称模糊 → projectId 集合（两段式，与 ShipmentServiceImpl 同范式，不拼 SQL 无注入）。
        // fin_receivable 只有 project_id 没有项目名称列，只能先反查项目。
        List<Long> nameIds = null;
        if (nameKw != null) {
            nameIds = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                            .like(Project::getProjectName, nameKw).select(Project::getId))
                    .stream().map(Project::getId).filter(Objects::nonNull).toList();
            if (nameIds.isEmpty()) {
                return new Page<>(current, size, 0);
            }
        }

        Long me = DataScopeHelper.currentUserId(userMapper);
        if (DataScopeHelper.denyAll(me)) {
            return new Page<>(current, size, 0);
        }
        LambdaQueryWrapper<Receivable> qw = new LambdaQueryWrapper<Receivable>()
                .eq(projectId != null, Receivable::getProjectId, projectId)
                .eq(statusEq != null, Receivable::getStatus, statusEq)
                .eq(sourceEq != null, Receivable::getSourceType, sourceEq)
                .like(noKw != null, Receivable::getReceivableNo, noKw)
                .in(nameIds != null, Receivable::getProjectId, nameIds)
                .ge(dateFrom != null, Receivable::getReceivableDate, dateFrom)
                .le(dateTo != null, Receivable::getReceivableDate, dateTo)
                .orderByDesc(Receivable::getCreatedAt);
        // V85：用户级隔离（2026-10-30 虎哥）：非管理员仅看「申请人 OR 发货人」
        if (!SecurityContextUtils.isAdmin()) {
            qw.and(w -> {
                w.eq(Receivable::getApplicantId, me).or().eq(Receivable::getShipperId, me);
            });
        }
        IPage<Receivable> page = receivableMapper.selectPage(new Page<>(current, size), qw);
        Map<String, DictData> statusDict = dictMap(DICT_RECEIVABLE_STATUS);
        Map<String, DictData> docDict = dictMap(DICT_DOC_DIRECTION);
        Map<String, DictData> sourceDict = dictMap(DICT_RECEIVABLE_SOURCE);
        // 项目信息批量预取：一页最多 500 条，逐条 selectById 会放大成 500 次查询
        Map<Long, Project> projects =
                BatchLoader.projects(projectMapper, page.getRecords(), Receivable::getProjectId);
        return page.convert(r -> toVO(r, statusDict, docDict, sourceDict, projects));
    }

    /** V59：累加 fin_project.receivable_amount（金额纪律：null 当 0，add） */
    private void addToProjectReceivable(Long projectId, BigDecimal delta) {
        if (projectId == null || delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            return;
        }
        BigDecimal base = p.getReceivableAmount() == null ? BigDecimal.ZERO : p.getReceivableAmount();
        p.setReceivableAmount(base.add(delta));
        projectMapper.updateById(p);
    }

    /** 金额 = Σ 已收数量 × 单价（决策#2：金额按 receivedQty 计算，收货确认才确认金额；首建/续收共用一份口径） */
    private BigDecimal cumulativeReceivedAmount(Long shipmentId) {
        return shipmentLineMapper.selectList(new LambdaQueryWrapper<ShipmentLine>()
                        .eq(ShipmentLine::getShipmentId, shipmentId))
                .stream()
                .map(line -> (line.getUnitPrice() != null && line.getReceivedQty() != null)
                        ? line.getUnitPrice().multiply(BigDecimal.valueOf(line.getReceivedQty()))
                        : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 当日序号自增（同 ShipmentServiceImpl.generateBatchNo 范式；uk_tenant_receivable_no 兜底防重） */
    private String generateReceivableNo() {
        String date = LocalDateTime.now().format(RECEIVABLE_NO_DATE_FMT);
        Long count = receivableMapper.selectCount(new LambdaQueryWrapper<Receivable>()
                .likeRight(Receivable::getReceivableNo, "YS-" + date + "-"));
        return String.format("YS-%s-%03d", date, (count == null ? 0 : count) + 1);
    }

    private ReceivableVO toVO(Receivable r, Map<String, DictData> statusDict,
                              Map<String, DictData> docDict, Map<String, DictData> sourceDict,
                              Map<Long, Project> projects) {
        ReceivableVO vo = new ReceivableVO();
        vo.setId(r.getId());
        vo.setReceivableNo(r.getReceivableNo());
        vo.setProjectId(r.getProjectId());
        // 项目信息由调用方预取传入（page 批量一次查，避免逐条 selectById 的 N+1）
        Project proj = r.getProjectId() == null ? null : projects.get(r.getProjectId());
        vo.setProjectNo(proj != null ? proj.getProjectNo() : null);
        vo.setProjectName(proj != null ? proj.getProjectName() : null);
        vo.setShipmentId(r.getShipmentId());
        vo.setDocDirection(r.getDocDirection());
        DictData doc = docDict.get(r.getDocDirection());
        vo.setDocDirectionName(doc != null ? doc.getDictLabel() : null);
        vo.setReceivableAmount(r.getReceivableAmount());
        vo.setWrittenOffAmount(r.getWrittenOffAmount());
        vo.setUnwrittenOffAmount(r.getUnwrittenOffAmount());
        vo.setInvoicedAmount(r.getInvoicedAmount());
        vo.setUninvoicedAmount(r.getUninvoicedAmount());
        vo.setReceivedAmount(r.getReceivedAmount());
        vo.setUnreceivedAmount(r.getUnreceivedAmount());
        vo.setReceivableDate(r.getReceivableDate());
        vo.setStatus(r.getStatus());
        DictData st = statusDict.get(r.getStatus());
        vo.setStatusName(st != null ? st.getDictLabel() : null);
        vo.setStatusStyle(st != null ? st.getStyle() : null);
        vo.setSourceType(r.getSourceType());
        DictData src = sourceDict.get(r.getSourceType());
        vo.setSourceTypeName(src != null ? src.getDictLabel() : null);
        return vo;
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
}
