package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.ReceivableVO;

/**
 * 应收单服务（由发货「客户已收货」时点自动生成，决策#2）。
 *
 * <p>1 发货 = 1 应收单（fin_receivable.shipment_id 唯一键）；后续回款核销、
 * 开票均以本单为载体。本服务只负责「生成」，状态推进（核销/开票）由各自的
 * WriteOffService / InvoiceService 负责（后续流程）。</p>
 */
public interface ReceivableService {

    /**
     * 由发货单（客户已收货）生成应收单。幂等：该发货单已生成过则直接返回既有应收单 id，
     * 避免 uk_receivable_shipment 唯一约束撞 DuplicateKey。
     *
     * @param shipmentId 发货单 ID
     * @param operatorId 触发人（= 客户已收货确认人，写 created_by）
     * @return 应收单 ID
     */
    Long createFromShipment(Long shipmentId, Long operatorId);

    /** 应收单详情（含字典翻译） */
    ReceivableVO detail(Long id);

    /** 应收单分页列表（projectId/status 精确；字典翻译） */
    IPage<ReceivableVO> page(Integer pageNum, Integer pageSize, Long projectId, String status);
}
