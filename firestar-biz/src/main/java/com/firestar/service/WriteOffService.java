package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.AutoMatchResultVO;
import com.firestar.dto.DifferencePoolVO;
import com.firestar.dto.ReverseRequest;
import com.firestar.dto.WriteOffLedgerVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 核销台账（2026-10-25 阶段一，虎哥拍板：先核销台账后退货）。
 *
 * <p>定位：V65 把「收款核销（fin_write_off）+ 开票核销（fin_invoice_allocation）」的
 * 账务能力补全了，但缺一个横切视图——核销动作散落在收款/发票详情里，没有
 * 「所有核销记录按时间序一屏可查」的台账，也没有差异池（待配对）和显式反核销入口。
 * 本模块补这一层，不动任何账务核心（滚数字全部复用既有范式）。</p>
 *
 * <p>阶段二退货的「反核销原核销记录」动作直接消费本模块的 reverse + 台账，
 * 所以先做本模块（无依赖、独立可验收）。</p>
 */
public interface WriteOffService {

    /**
     * 核销台账分页（fin_write_off + fin_invoice_allocation 双表合并，createdAt 倒序）。
     *
     * <p>明细表无 project_id 列：传 projectId 时先收敛应收单 ID 集合再 in 查
     * （本地测试数据量下双表 selectList + 内存合并分页，同 ProjectServiceImpl.amountsOf
     * 范式；跨表 UNION 需手写 XML，破 V1「零手写 SQL」纪律，不做）。</p>
     *
     * @param ledgerType RECEIPT=仅回款核销 / INVOICE=仅开票核销 / null=全部
     * @param status     NORMAL / REVERSED / null=全部
     * @param keyword    核销单号模糊（WO-/IA-）
     */
    IPage<WriteOffLedgerVO> pageLedger(Integer pageNum, Integer pageSize, Long projectId,
                                       String ledgerType, String status, String keyword,
                                       LocalDate dateFrom, LocalDate dateTo);

    /**
     * 差异池：按项目分组的「未核销收款单 × 未回款应收单」待配对视图。
     *
     * <p>收款单侧：RECEIVED + 非红冲 + unwritten_off_amount &gt; 0；
     * 应收单侧：unreceived_amount &gt; 0。自动核销只同项目内配对。</p>
     */
    List<DifferencePoolVO> differencePool(Long projectId);

    /**
     * 自动核销（虎哥拍板 auto）：同项目内金额贪心匹配，日期早优先。
     *
     * <p>逐收款单（收款日期升序）顺序吃应收单余额（应收日期升序），
     * 每配一对立即落 fin_write_off（method=AUTO）+ 滚应收单 received/unreceived
     * + 重算收款单 written/unwritten，单事务。全部配不上抛异常（不产生半截结果）。</p>
     */
    AutoMatchResultVO autoMatch(Long projectId);

    /**
     * 反核销（V1 范式「一层不可删只能反核销」）：原行置 REVERSED 留痕 +
     * originalLedgerId 回填自身 + ext.reverseReason/reversedBy/reversedAt，
     * 回滚应收单数字并重算主单已核销口径。原单为红冲单（RED）时回滚方向取反。
     */
    void reverse(ReverseRequest req);
}
