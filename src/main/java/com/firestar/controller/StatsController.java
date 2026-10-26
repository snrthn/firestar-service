package com.firestar.controller;

import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.ArSummaryVO;
import com.firestar.dto.FulfillmentVO;
import com.firestar.dto.InvoiceSummaryVO;
import com.firestar.dto.OverviewVO;
import com.firestar.dto.ProjectRankingVO;
import com.firestar.dto.StatsQuery;
import com.firestar.dto.AgeingVO;
import com.firestar.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 看板只读统计接口（L1）。
 *
 * <p>权限：dashboard:view（种子 V4 已授予 ROLE_ADMIN / 出纳 / 开票 / 主管 / 经理）。
 * 全部只读，不写库。前端各 Tab 据此取数；账龄/DSO 等 L2 指标后续并入 ArSummaryVO。</p>
 */
@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
@RequirePerm("dashboard:view")
public class StatsController {

    private final StatsService statsService;

    /** Tab① 经营总览 */
    @PostMapping("/overview")
    public Result<OverviewVO> overview(@RequestBody(required = false) StatsQuery q) {
        return Result.success(statsService.overview(q == null ? new StatsQuery() : q));
    }

    /** Tab② 应收与回款（摘要） */
    @PostMapping("/ar")
    public Result<ArSummaryVO> ar(@RequestBody(required = false) StatsQuery q) {
        return Result.success(statsService.arSummary(q == null ? new StatsQuery() : q));
    }

    /** Tab③ 开票与票税 */
    @PostMapping("/invoice")
    public Result<InvoiceSummaryVO> invoice(@RequestBody(required = false) StatsQuery q) {
        return Result.success(statsService.invoiceSummary(q == null ? new StatsQuery() : q));
    }

    /** Tab④ 项目与销售 */
    @PostMapping("/project")
    public Result<ProjectRankingVO> project(@RequestBody(required = false) StatsQuery q) {
        return Result.success(statsService.projectRanking(q == null ? new StatsQuery() : q));
    }

    /** Tab⑤ 履约与风控 */
    @PostMapping("/fulfillment")
    public Result<FulfillmentVO> fulfillment(@RequestBody(required = false) StatsQuery q) {
        return Result.success(statsService.fulfillment(q == null ? new StatsQuery() : q));
    }

    /** Tab② L2 账龄与周转（FIFO） */
    @PostMapping("/ageing")
    public Result<AgeingVO> ageing(@RequestBody(required = false) StatsQuery q) {
        return Result.success(statsService.ageing(q == null ? new StatsQuery() : q));
    }
}
