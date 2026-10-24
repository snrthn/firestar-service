package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.ReceivableVO;
import com.firestar.service.ReceivableService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应收单接口（前端 /api/receivable/**，路由不带 /api，同 ProjectController 约定）。
 *
 * <p>权限点 receivable:view（ROLE_ADMIN 有保险网可直接调通）。
 * 仅提供「详情 / 列表」只读能力；生成逻辑由 ShipmentServiceImpl 在「客户已收货」时触发，
 * 写入逻辑在 ReceivableServiceImpl 内部，不在此暴露写接口。</p>
 */
@RestController
@RequirePerm("receivable:view")
@RequestMapping("/receivable")
public class ReceivableController {

    private final ReceivableService receivableService;

    public ReceivableController(ReceivableService receivableService) {
        this.receivableService = receivableService;
    }

    /** 分页列表（projectId/status 精确；字典翻译） */
    @GetMapping("/page")
    public Result<IPage<ReceivableVO>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status) {
        return Result.success(receivableService.page(pageNum, pageSize, projectId, status));
    }

    /** 详情（含字典翻译） */
    @GetMapping("/{id}")
    public Result<ReceivableVO> detail(@PathVariable Long id) {
        return Result.success(receivableService.detail(id));
    }
}
