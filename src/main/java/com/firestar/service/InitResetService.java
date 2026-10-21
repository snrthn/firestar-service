package com.firestar.service;

import com.firestar.dto.InitRequest;
import com.firestar.dto.InitStatusVO;
import com.firestar.dto.ResetRequest;
import com.firestar.dto.EnvCheckItem;
import com.firestar.dto.ResetResultVO;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 系统初始化与重置（安装向导）。
 *
 * <p>设计对齐《业务模型》第 15/16 章：
 * <ul>
 *   <li>GET status：未初始化时公开，供前端判断跳转安装向导；</li>
 *   <li>POST init：未初始化时公开，环境检查 + 初始化超管 + 翻转完成标记；
 *       数据库 DDL、RBAC/字典/菜单种子已由 Flyway 在启动时完成，故 init 不重复播种；</li>
 *   <li>reset(light/full)：仅试运行模式（trial_mode=true）可用，管理员权限；
 *       需二次确认词 + 验证码；完成后写审计日志。</li>
 * </ul>
 *
 * <p>安全：reset 端点要求 {@code @RequirePerm("system:reset")}；trial_mode=false 时返回 403。
 */
public interface InitResetService {

    /** 检查初始化与试运行状态 */
    InitStatusVO getStatus();

    /** 执行初始化安装向导（仅未初始化时可调用） */
    InitStatusVO init(InitRequest req);

    /** 环境预检（安装向导 /install 页展示用，未初始化时公开，不拦截） */
    List<EnvCheckItem> precheck();

    /** 执行系统重置（light=轻度 / full=完整）。首次调用返回验证码，二次调用携带验证码执行 */
    ResetResultVO reset(String type, ResetRequest req, HttpServletRequest request);
}
