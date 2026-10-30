package com.firestar.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.common.IpUtil;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.AgreementVO;
import com.firestar.entity.AgreementVersion;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.service.AgreementService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 协议接口（系统管理模块）。
 *
 * 【路径约定】同 DictController：后端路由不带 /api；除登录页外默认需 JWT，
 * SecurityConfig 白名单未放行本路径，天然受保护。
 *
 * 【权限】查看/同意/撤回 = 登录即可（无 @RequirePerm）；发布版本/版本历史 = @RequirePerm("config:manage")。
 */
@RestController
@RequestMapping("/system/agreement")
public class AgreementController {

    private final AgreementService agreementService;
    private final UserMapper userMapper;

    public AgreementController(AgreementService agreementService, UserMapper userMapper) {
        this.agreementService = agreementService;
        this.userMapper = userMapper;
    }

    /** 登录后告知：当前生效版本（含全文），前端「协议展示」复用 */
    @GetMapping("/active")
    public Result<List<AgreementVO>> active() {
        return Result.success(agreementService.listActive());
    }

    /** 强制确认弹层：当前用户尚未对生效版本做出 AGREE 的协议 */
    @GetMapping("/pending")
    public Result<List<AgreementVO>> pending() {
        return Result.success(agreementService.listPending(currentUserId()));
    }

    /** 个人中心「隐私与协议」Tab：每个协议当前用户对生效版本的最后动作与时间 */
    @GetMapping("/consents")
    public Result<List<AgreementVO>> consents() {
        return Result.success(agreementService.listConsents(currentUserId()));
    }

    /** 同意（只增，action=AGREE） */
    @PostMapping("/{agreementId}/consent")
    public Result<Void> consent(@PathVariable Long agreementId,
                                @RequestBody ConsentRequest req,
                                HttpServletRequest request) {
        String channel = req.getChannel() == null ? "LOGIN" : req.getChannel();
        agreementService.consent(currentUserId(), agreementId, req.getVersionId(),
                channel, IpUtil.getClientIp(request), request.getHeader("User-Agent"));
        return Result.success();
    }

    /** 撤回（只增，action=WITHDRAW，不删旧 AGREE 记录） */
    @PostMapping("/{agreementId}/withdraw")
    public Result<Void> withdraw(@PathVariable Long agreementId,
                                 @RequestBody WithdrawRequest req,
                                 HttpServletRequest request) {
        agreementService.withdraw(currentUserId(), agreementId, req.getVersionId(),
                IpUtil.getClientIp(request), request.getHeader("User-Agent"));
        return Result.success();
    }

    /** 协议版本历史（管理员） */
    @RequirePerm("config:manage")
    @GetMapping("/versions/{agreementId}")
    public Result<List<AgreementVersion>> versions(@PathVariable Long agreementId) {
        return Result.success(agreementService.listVersions(agreementId));
    }

    /** 发布新版本（管理员） */
    @RequirePerm("agreement:publish")
    @PostMapping("/version")
    public Result<Long> publish(@RequestBody PublishVersionRequest req) {
        Long id = agreementService.publishVersion(req.getAgreementId(), req.getContent(),
                req.getChangeSummary(), req.getNeedReconsent(), currentUserId());
        return Result.success(id);
    }

    private Long currentUserId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).select(User::getId));
        if (u == null) {
            throw new IllegalStateException("当前登录用户不存在");
        }
        return u.getId();
    }

    // ===== 请求体 =====

    @Getter
    @Setter
    public static class ConsentRequest {
        private Long versionId;
        private String channel;
    }

    @Getter
    @Setter
    public static class WithdrawRequest {
        private Long versionId;
    }

    @Getter
    @Setter
    public static class PublishVersionRequest {
        private Long agreementId;
        private String content;
        private String changeSummary;
        private Boolean needReconsent;
    }
}
