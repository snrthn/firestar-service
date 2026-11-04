package com.firestar.controller;

import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.EnvCheckItem;
import com.firestar.dto.InitRequest;
import com.firestar.dto.InitStatusVO;
import com.firestar.dto.ResetRequest;
import com.firestar.dto.ResetResultVO;
import com.firestar.service.InitResetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统初始化与重置（安装向导）控制器。
 *
 * <p>路径（后端真实路径，不含 /api 代理前缀）：
 * <ul>
 *   <li>GET  /system/init/status  检查初始化状态（未初始化时公开）</li>
 *   <li>POST /system/init         执行初始化（未初始化时公开）</li>
 *   <li>POST /system/reset/light  轻度重置（管理员 + 试运行模式）</li>
 *   <li>POST /system/reset/full   完整重置（管理员 + 试运行模式）</li>
 * </ul>
 *
 * <p>init 两条匿名白名单见 {@code SecurityConfig.ALWAYS_PUBLIC}；
 * reset 两条需 {@code @RequirePerm("system:reset")}（ROLE_ADMIN 自动放行）。
 */
@RestController
@RequestMapping("/system")
public class InitResetController {

    private final InitResetService initResetService;

    public InitResetController(InitResetService initResetService) {
        this.initResetService = initResetService;
    }

    /** 检查初始化状态（未初始化时公开，供安装向导跳转判断） */
    @GetMapping("/init/status")
    public Result<InitStatusVO> status() {
        InitStatusVO vo = initResetService.getStatus();
        // 信息暴露收敛（P2 已降级）：初始化完成后安装向导已关闭，匿名端点只回 initialized:true，
        // 不再回显 trialMode / canReset / adminExists，避免被探测「管理员是否存在 / 是否试运行」。
        // 仅在此匿名端点裁剪；init() 内部复用 getStatus()，返回完整状态给安装向导，不受影响。
        if (vo.isInitialized()) {
            InitStatusVO minimal = new InitStatusVO();
            minimal.setInitialized(true);
            return Result.success(minimal);
        }
        return Result.success(vo);
    }

    /** 执行初始化安装向导（未初始化时公开） */
    @PostMapping("/init")
    public Result<InitStatusVO> init(@RequestBody(required = false) InitRequest req) {
        if (req == null) req = new InitRequest();
        return Result.success(initResetService.init(req));
    }

    /** 环境预检（安装向导展示用，未初始化时公开） */
    @GetMapping("/init/precheck")
    public Result<List<EnvCheckItem>> precheck() {
        return Result.success(initResetService.precheck());
    }

    /** 轻度重置（保留系统配置，仅清业务数据 + Flowable 运行时/历史 + 上传文件） */
    @RequirePerm("system:reset")
    @PostMapping("/reset/light")
    public Result<ResetResultVO> resetLight(@RequestBody ResetRequest req, HttpServletRequest request) {
        return Result.success(initResetService.reset("light", req, request));
    }

    /** 完整重置（恢复出厂：Flyway 清库重建 + 重新播种，需重新执行初始化向导） */
    @RequirePerm("system:reset")
    @PostMapping("/reset/full")
    public Result<ResetResultVO> resetFull(@RequestBody ResetRequest req, HttpServletRequest request) {
        return Result.success(initResetService.reset("full", req, request));
    }
}
