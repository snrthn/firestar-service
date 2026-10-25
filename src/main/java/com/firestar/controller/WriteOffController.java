package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.AutoMatchResultVO;
import com.firestar.dto.DifferencePoolVO;
import com.firestar.dto.ReverseRequest;
import com.firestar.dto.WriteOffLedgerVO;
import com.firestar.service.WriteOffService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 核销台账接口（前端 /writeoff/**，路由不带 /api，同 ReceiptController 约定）。
 *
 * <p>2026-10-25 阶段一：核销记录统一台账 + 差异池 + 自动核销 + 反核销。
 * 权限点 writeoff:manage（与 receipt:manage 并列，菜单种子随阶段一前端一并种）。</p>
 */
@RestController
@RequirePerm("writeoff:manage")
@RequestMapping("/writeoff")
public class WriteOffController {

    private final WriteOffService writeOffService;

    @Autowired
    public WriteOffController(WriteOffService writeOffService) {
        this.writeOffService = writeOffService;
    }

    /**
     * 核销台账分页（fin_write_off + fin_invoice_allocation 合并，createdAt 倒序）。
     *
     * @param ledgerType RECEIPT=仅回款核销 / INVOICE=仅开票核销 / 不传=全部
     * @param status     NORMAL / REVERSED / 不传=全部
     */
    @GetMapping("/page")
    public Result<IPage<WriteOffLedgerVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String ledgerType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        return Result.success(writeOffService.pageLedger(
                pageNum, pageSize, projectId, ledgerType, status, keyword, dateFrom, dateTo));
    }

    /** 差异池：按项目分组的「未核销收款单 × 未回款应收单」待配对视图 */
    @GetMapping("/difference-pool")
    public Result<List<DifferencePoolVO>> differencePool(
            @RequestParam(required = false) Long projectId) {
        return Result.success(writeOffService.differencePool(projectId));
    }

    /**
     * 自动核销（同项目内金额贪心、日期优先，method=AUTO）。
     *
     * <p>全部配不上抛 400（不产生半截结果）；配上的部分单事务落账，
     * 结果回传配对明细供前端展示「这轮自动核销干了什么」。</p>
     */
    @PostMapping("/auto-match")
    public Result<AutoMatchResultVO> autoMatch(@RequestParam Long projectId) {
        return Result.success(writeOffService.autoMatch(projectId));
    }

    /**
     * 反核销：原行置 REVERSED 留痕 + 回滚应收单 + 重算主单口径。
     * 原单为红冲单时回滚方向取反（V1「一层不可删只能反核销」范式）。
     */
    @PostMapping("/reverse")
    public Result<Void> reverse(@RequestBody ReverseRequest req) {
        writeOffService.reverse(req);
        return Result.success();
    }
}
