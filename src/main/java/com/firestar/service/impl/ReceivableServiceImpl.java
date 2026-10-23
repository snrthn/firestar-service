package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.entity.Receivable;
import com.firestar.entity.Shipment;
import com.firestar.entity.ShipmentLine;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.mapper.ShipmentLineMapper;
import com.firestar.mapper.ShipmentMapper;
import com.firestar.service.ReceivableService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 应收单生成实现：客户已收货 → 蓝字应收单（doc_direction=BLUE, source_type=FROM_SHIPMENT）。
 *
 * <p>tenant_id / version 不手动 set（BaseEntity 约定：由插件/拦截器管，业务代码不碰）。</p>
 */
@Slf4j
@Service
public class ReceivableServiceImpl implements ReceivableService {

    /**
     * 应收单号规则：YS-{yyyyMMdd}-{三位序号}（当日自增，与发货批次号同范式）。
     *
     * <p>⚠️ 必须「先算后插」：fin_receivable.receivable_no 是 NOT NULL 无默认列
     * （V1 基线），曾用「先 insert 占 id 再回写」范式（对齐 project_no 的可空列），
     * 首次 insert 即撞 Field 'receivable_no' doesn't have a default value（2026-10-23 实锤）。</p>
     */
    private static final DateTimeFormatter RECEIVABLE_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReceivableMapper receivableMapper;
    private final ShipmentMapper shipmentMapper;
    private final ShipmentLineMapper shipmentLineMapper;

    @Autowired
    public ReceivableServiceImpl(ReceivableMapper receivableMapper,
                                ShipmentMapper shipmentMapper,
                                ShipmentLineMapper shipmentLineMapper) {
        this.receivableMapper = receivableMapper;
        this.shipmentMapper = shipmentMapper;
        this.shipmentLineMapper = shipmentLineMapper;
    }

    @Override
    public Long createFromShipment(Long shipmentId, Long operatorId) {
        // 幂等：同发货单已生成过应收单则「金额重算后复用」（2026-10-24 虎哥：部分收货可续收，
        // 第二次收货必须把新增已收金额补进同一张应收单——既不能重复建单，也不能金额停在首收值）
        Receivable exist = receivableMapper.selectOne(new LambdaQueryWrapper<Receivable>()
                .eq(Receivable::getShipmentId, shipmentId));
        if (exist != null) {
            BigDecimal amount = cumulativeReceivedAmount(shipmentId);
            exist.setReceivableAmount(amount);
            // 已核销/已开票保持不动（可能已有后续核销/开票动作），未核销/未开票 = 应收 - 对应已发生额
            BigDecimal writtenOff = exist.getWrittenOffAmount() == null ? BigDecimal.ZERO : exist.getWrittenOffAmount();
            BigDecimal invoiced = exist.getInvoicedAmount() == null ? BigDecimal.ZERO : exist.getInvoicedAmount();
            exist.setUnwrittenOffAmount(amount.subtract(writtenOff));
            exist.setUninvoicedAmount(amount.subtract(invoiced));
            exist.setUpdatedBy(operatorId);
            receivableMapper.updateById(exist);
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
        r.setSourceType("FROM_SHIPMENT");  // 发货生成（V2 字典 receivable_source=FROM_SHIPMENT）
        r.setReceivableAmount(amount);
        r.setWrittenOffAmount(BigDecimal.ZERO);
        r.setUnwrittenOffAmount(amount);
        r.setInvoicedAmount(BigDecimal.ZERO);
        r.setUninvoicedAmount(amount);
        r.setReceivableDate(LocalDate.now());
        r.setStatus("CONFIRMED");          // 客户已收货 = 金额已确认（V2 字典 receivable_status=CONFIRMED）
        r.setReceivableNo(generateReceivableNo()); // NOT NULL 无默认列：插入前必须算好
        r.setCreatedBy(operatorId);
        receivableMapper.insert(r);

        log.info("[receivable] 由发货单生成应收单 id={} no={} amount={}", r.getId(), r.getReceivableNo(), amount);
        return r.getId();
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
}
