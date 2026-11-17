package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.PublishNotificationRequest;
import com.firestar.entity.LoginFail;
import com.firestar.entity.LoginLog;
import com.firestar.entity.Role;
import com.firestar.entity.UserRole;
import com.firestar.exception.LoginRateLimitException;
import com.firestar.mapper.LoginFailMapper;
import com.firestar.mapper.LoginIpLimitMapper;
import com.firestar.mapper.LoginLogMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.LoginProtectService;
import com.firestar.service.LogService;
import com.firestar.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 登录爆破防护实现（L1 + L2 + 审计闭环）。
 *
 * <p>全部状态落 MySQL（firestar 无 Redis）。账户锁定用 {@code sys_login_fail}（唯一索引
 * tenant_id+username，原子自增）；IP 限流用 {@code sys_login_ip_limit}（固定窗口）。
 * 锁定/解锁动作均写入 {@code sys_login_log}（login_status = LOCKED / UNLOCKED），并通过
 * {@link NotificationService#publish} 向全体管理员推送安全通知——该通知落 {@code sys_notification}
 * 并广播 NOTIFY，前端通知中心铃铛与右下角轻提醒据此消费，构成「检测→响应→记录响应→告警→恢复→记录恢复」
 * 的完整闭环。
 *
 * <p>⚠️ 告警不自己广播裸事件：早期版本直接广播 SECURITY_ALERT，但前端无对应消费者（落空）。
 * 现统一经通知中枢，复用既有铃铛 + 轻提醒，避免再造一个无人接的实时通道。
 */
@Service
@Slf4j
public class LoginProtectServiceImpl implements LoginProtectService {

    private static final long TENANT_ID = 0L;
    private static final String ADMIN_ROLE_CODE = "ROLE_ADMIN";

    private final LoginFailMapper failMapper;
    private final LoginIpLimitMapper ipMapper;
    private final LoginLogMapper loginLogMapper;
    private final LogService logService;
    private final NotificationService notificationService;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;

    @Value("${security.login.max-retry-count:5}")
    private int maxRetryCount;

    @Value("${security.login.lock-seconds:900}")
    private int lockSeconds;

    @Value("${security.login.ip-limit-count:20}")
    private int ipLimitCount;

    @Value("${security.login.ip-limit-window-seconds:60}")
    private int ipWindowSeconds;

    @Value("${security.login.alert-enabled:true}")
    private boolean alertEnabled;

    public LoginProtectServiceImpl(LoginFailMapper failMapper,
                                   LoginIpLimitMapper ipMapper,
                                   LoginLogMapper loginLogMapper,
                                   LogService logService,
                                   NotificationService notificationService,
                                   RoleMapper roleMapper,
                                   UserRoleMapper userRoleMapper) {
        this.failMapper = failMapper;
        this.ipMapper = ipMapper;
        this.loginLogMapper = loginLogMapper;
        this.logService = logService;
        this.notificationService = notificationService;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
    }

    @Override
    public void onLoginFail(Long userId, String username, String clientIp, String userAgent) {
        failMapper.incrementAndCreate(TENANT_ID, username);
        failMapper.lockIfThreshold(TENANT_ID, username, lockSeconds, maxRetryCount);

        LoginFail lf = failMapper.byUsername(TENANT_ID, username);
        if (lf != null && lf.getLockedUntil() != null && lf.getLockedUntil().isAfter(LocalDateTime.now())) {
            int minutes = Math.max(1, lockSeconds / 60);
            recordSecurityEvent(userId, username, "LOCKED", clientIp, userAgent,
                    "连续 " + maxRetryCount + " 次登录失败，账号锁定 " + minutes + " 分钟");
            alert("ACCOUNT_LOCKED", username, clientIp, "账号因多次登录失败被锁定");
        }
    }

    @Override
    public void onLoginSuccess(String username) {
        failMapper.delete(new LambdaQueryWrapper<LoginFail>()
                .eq(LoginFail::getTenantId, TENANT_ID)
                .eq(LoginFail::getUsername, username));
    }

    @Override
    public boolean isLocked(String username) {
        LoginFail lf = failMapper.byUsername(TENANT_ID, username);
        return lf != null && lf.getLockedUntil() != null && lf.getLockedUntil().isAfter(LocalDateTime.now());
    }

    @Override
    public long getRemainingLockSeconds(String username) {
        LoginFail lf = failMapper.byUsername(TENANT_ID, username);
        if (lf == null || lf.getLockedUntil() == null) {
            return 0L;
        }
        long secs = Duration.between(LocalDateTime.now(), lf.getLockedUntil()).getSeconds();
        return Math.max(0L, secs);
    }

    @Override
    public void unlock(String username, String operator) {
        failMapper.delete(new LambdaQueryWrapper<LoginFail>()
                .eq(LoginFail::getTenantId, TENANT_ID)
                .eq(LoginFail::getUsername, username));
        recordSecurityEvent(null, username, "UNLOCKED", null, null,
                "管理员 " + operator + " 手动解锁");
        alert("ACCOUNT_UNLOCKED", username, null, "账号被管理员解锁");
    }

    @Override
    public void acquire(String ip) {
        if (ip == null || ip.isBlank()) {
            ip = "unknown";
        }
        ipMapper.incrementAndCreate(TENANT_ID, ip, ipWindowSeconds);
        Integer count = ipMapper.selectReqCount(TENANT_ID, ip);
        if (count != null && count > ipLimitCount) {
            throw new LoginRateLimitException("登录尝试过于频繁，请稍后再试");
        }
    }

    private void recordSecurityEvent(Long userId, String username, String status,
                                     String ip, String ua, String reason) {
        try {
            LoginLog log = new LoginLog();
            log.setUserId(userId);
            log.setUsername(username);
            log.setLoginStatus(status);
            log.setIpAddress(ip);
            log.setUserAgent(ua);
            log.setFailReason(reason);
            log.setLoginAt(LocalDateTime.now());
            logService.saveLogin(log);
        } catch (Exception e) {
            // 安全审计旁路失败不应阻断主流程
            log.warn("登录安全事件审计写入失败(status={}, username={}): {}", status, username, e.getMessage());
        }
    }

    /**
     * 安全告警：向全体管理员推送通知（落 sys_notification + 广播 NOTIFY → 前端铃铛 + 轻提醒）。
     * 不自行广播裸实时事件——前端无独立 SECURITY_ALERT 消费者，统一走通知中枢避免造无人接的通道。
     */
    private void alert(String type, String username, String ip, String message) {
        if (!alertEnabled) {
            return;
        }
        try {
            List<Long> adminIds = resolveAdminUserIds();
            if (adminIds.isEmpty()) {
                log.warn("安全告警({})无管理员接收人，跳过推送: username={}", type, username);
                return;
            }
            PublishNotificationRequest req = new PublishNotificationRequest();
            req.setReceivers(adminIds);
            if ("ACCOUNT_LOCKED".equals(type)) {
                req.setTitle("账号锁定安全告警");
                req.setContent("账号 " + username + " 因连续登录失败已被锁定"
                        + (ip != null ? "（来源 IP：" + ip + "）" : "")
                        + "。请关注是否存在爆破行为，必要时手动解锁。");
            } else {
                req.setTitle("账号解锁通知");
                req.setContent("账号 " + username + " 已被管理员手动解锁。");
            }
            req.setLevel("URGENT");
            req.setLinkUrl("/log/login");
            notificationService.publish(req, null);
        } catch (Exception e) {
            // 告警推送失败绝不应阻断登录主流程
            log.warn("安全告警发布失败(type={}, username={}): {}", type, username, e.getMessage());
        }
    }

    /** 解析全体 ROLE_ADMIN 用户 id（作为安全告警接收人）。 */
    private List<Long> resolveAdminUserIds() {
        Role adminRole = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleCode, ADMIN_ROLE_CODE));
        if (adminRole == null) {
            return new ArrayList<>();
        }
        List<UserRole> urs = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRoleId, adminRole.getId()));
        List<Long> ids = new ArrayList<>();
        for (UserRole ur : urs) {
            if (ur.getUserId() != null && !ids.contains(ur.getUserId())) {
                ids.add(ur.getUserId());
            }
        }
        return ids;
    }
}
