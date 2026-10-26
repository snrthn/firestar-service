package com.firestar.service;

import com.firestar.dto.ArSummaryVO;
import com.firestar.dto.FulfillmentVO;
import com.firestar.dto.InvoiceSummaryVO;
import com.firestar.dto.OverviewVO;
import com.firestar.dto.ProjectRankingVO;
import com.firestar.dto.StatsQuery;
import com.firestar.dto.AgeingVO;

/**
 * 看板只读统计层（L1 + L2）。
 *
 * <p>约定：
 * <ul>
 *   <li>全部方法只读，绝不写库；</li>
 *   <li>口径契约以 docs/数据看板-指标体系设计.md 为准，核心红线：
 *       禁用 fin_receivable.written_off_amount / unwritten_off_amount（无写入方），
 *       回款额一律按 V65 的 received/unreceived 与 fin_write_off 核算；</li>
 *   <li>回款只认 approval_status='RECEIVED'；红字（doc_direction='RED'）与退款（receipt_type='REFUND'）金额取负；</li>
 *   <li>FIFO 账龄 / DSO / 应收周转率等由 L2 的 AgeingService 计算，本接口不含（ArSummaryVO 预留）。</li>
 * </ul>
 */
public interface StatsService {

    /** Tab① 经营总览 */
    OverviewVO overview(StatsQuery q);

    /** Tab② 应收与回款（摘要，不含账龄/DSO） */
    ArSummaryVO arSummary(StatsQuery q);

    /** Tab③ 开票与票税 */
    InvoiceSummaryVO invoiceSummary(StatsQuery q);

    /** Tab④ 项目与销售（按合同额降序的榜单） */
    ProjectRankingVO projectRanking(StatsQuery q);

    /** Tab⑤ 履约与风控（非 Flowable 指标） */
    FulfillmentVO fulfillment(StatsQuery q);

    /** Tab② L2 账龄与周转（FIFO 账龄五段 / DSO / 年化周转率 / 平均逾期天数） */
    AgeingVO ageing(StatsQuery q);
}
