package com.firestar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.LoginRequest;
import com.firestar.entity.LoginFail;
import com.firestar.entity.LoginLog;
import com.firestar.entity.User;
import com.firestar.exception.BizException;
import com.firestar.exception.LoginRateLimitException;
import com.firestar.mapper.LoginFailMapper;
import com.firestar.mapper.LoginLogMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.AuthService;
import com.firestar.service.LoginProtectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录爆破防护集成测试（L1 账户锁定 + L2 IP 限流 + 审计闭环）。
 * 连 firestar_test 集成测试库（@ActiveProfiles("it")）。阈值取 application-it.properties 的
 * 测试友好值：max-retry=3、ip-limit=5、lock-seconds=900。
 */
@SpringBootTest
@ActiveProfiles("it")
class LoginBruteForceTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private LoginProtectService protectService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private LoginLogMapper loginLogMapper;
    @Autowired
    private LoginFailMapper loginFailMapper;

    private static final String PWD = "Bf@123456";
    private final List<Long> createdIds = new ArrayList<>();
    private final List<String> createdUsernames = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // 清理失败计数行 + 测试用户，避免污染
        for (String u : createdUsernames) {
            loginFailMapper.delete(new LambdaQueryWrapper<LoginFail>()
                    .eq(LoginFail::getUsername, u));
        }
        createdIds.forEach(userMapper::deleteById);
        createdIds.clear();
        createdUsernames.clear();
    }

    private String createUser() {
        String username = "bf_" + System.nanoTime() + "_" + createdIds.size();
        User u = new User();
        u.setUsername(username);
        u.setPassword(new BCryptPasswordEncoder().encode(PWD));
        u.setNickname("bf");
        u.setStatus("ENABLED");
        userMapper.insert(u);
        createdIds.add(u.getId());
        createdUsernames.add(username);
        return username;
    }

    private LoginRequest lr(String username, String pwd) {
        LoginRequest r = new LoginRequest();
        r.setUsername(username);
        r.setPassword(pwd);
        return r;
    }

    private String ip() {
        return "127.0.0.1";
    }

    @Test
    @DisplayName("L1：连续失败达阈值后账号锁定，锁定期间正确密码也被拒")
    void l1Lock() {
        String u = createUser();
        for (int i = 0; i < 3; i++) {
            assertThrows(BizException.class, () -> authService.login(lr(u, "wrong"), ip(), "ua"));
        }
        assertTrue(protectService.isLocked(u));

        // 锁定期间即便密码正确也应被拒
        assertThrows(BizException.class, () -> authService.login(lr(u, PWD), ip(), "ua"));
    }

    @Test
    @DisplayName("L1：登录成功后清零失败计数")
    void l1ClearOnSuccess() {
        String u = createUser();
        assertThrows(BizException.class, () -> authService.login(lr(u, "wrong"), ip(), "ua"));
        assertThrows(BizException.class, () -> authService.login(lr(u, "wrong"), ip(), "ua"));
        // 第 3 次正确登录（未达阈值 3，不会锁）
        authService.login(lr(u, PWD), ip(), "ua");
        assertFalse(protectService.isLocked(u));
    }

    @Test
    @DisplayName("L1+L2 闭环：锁定写 LOCKED 审计，管理员解锁写 UNLOCKED 审计")
    void auditClosure() {
        String u = createUser();
        for (int i = 0; i < 3; i++) {
            assertThrows(BizException.class, () -> authService.login(lr(u, "wrong"), ip(), "ua"));
        }
        assertTrue(protectService.isLocked(u));

        long locked = loginLogMapper.selectCount(new LambdaQueryWrapper<LoginLog>()
                .eq(LoginLog::getUsername, u).eq(LoginLog::getLoginStatus, "LOCKED"));
        assertEquals(1, locked);

        protectService.unlock(u, "admin");
        assertFalse(protectService.isLocked(u));

        long unlocked = loginLogMapper.selectCount(new LambdaQueryWrapper<LoginLog>()
                .eq(LoginLog::getUsername, u).eq(LoginLog::getLoginStatus, "UNLOCKED"));
        assertEquals(1, unlocked);
    }

    @Test
    @DisplayName("L2：同 IP 超出限流阈值抛限流异常")
    void l2IpLimit() {
        String ip = "203.0.113.9";
        for (int i = 0; i < 5; i++) {
            protectService.acquire(ip);
        }
        assertThrows(LoginRateLimitException.class, () -> protectService.acquire(ip));
    }
}
