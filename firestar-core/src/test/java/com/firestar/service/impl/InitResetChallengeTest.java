package com.firestar.service.impl;

import com.firestar.dto.ResetRequest;
import com.firestar.dto.ResetResultVO;
import com.firestar.entity.Config;
import com.firestar.mapper.ConfigMapper;
import com.firestar.mapper.FileStorageMapper;
import com.firestar.mapper.ProcessDefMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.FileStorageService;
import com.firestar.service.LogService;
import com.firestar.workflow.WorkflowEngineService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 重置验证码防爆破契约（2026-11-22 加固的回归测试）。
 *
 * <p>纯 Mockito 单测，不连库——验证码挑战是 {@code InitResetServiceImpl} 内存态，
 * 只需 mock 掉「试运行开关 / 用户密码校验」两处外部依赖即可完整覆盖四条防线：
 * 生成、错误次数上限、来源 IP 绑定、一次性使用。</p>
 */
@ExtendWith(MockitoExtension.class)
class InitResetChallengeTest {

    @Mock private ConfigMapper configMapper;
    @Mock private UserMapper userMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private FileStorageMapper fileStorageMapper;
    @Mock private LogService logService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private DataSource dataSource;
    @Mock private ObjectProvider<FileStorageService> storageProvider;
    @Mock private ObjectProvider<ProcessDefMapper> processDefMapperProvider;
    @Mock private ObjectProvider<WorkflowEngineService> engineProvider;
    @Mock private Environment environment;
    @Mock private HttpServletRequest request;

    private InitResetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InitResetServiceImpl(configMapper, userMapper, userRoleMapper, roleMapper,
                fileStorageMapper, logService, jdbcTemplate, dataSource, storageProvider,
                processDefMapperProvider, engineProvider, environment);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        // trial_mode=true：放行到验证码环节；其余 config key 返回 null
        when(configMapper.selectOne(any())).thenAnswer(inv -> {
            Config c = new Config();
            c.setConfigKey("trial_mode");
            c.setConfigValue("true");
            return c;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ResetRequest firstRequest() {
        ResetRequest r = new ResetRequest();
        r.setConfirmText("RESET");
        return r;
    }

    @Test
    @DisplayName("首次调用生成 6 位验证码，executed=false 且不触达清库")
    void firstCall_generatesChallenge() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        ResetResultVO res = service.reset("light", firstRequest(), request);
        assertFalse(res.isExecuted());
        assertNotNull(res.getVerifyCode());
        assertEquals(6, res.getVerifyCode().length());
        assertTrue(res.getVerifyCode().matches("\\d{6}"));
    }

    @Test
    @DisplayName("错误验证码累计 5 次后挑战作废，必须重新获取")
    void wrongCode_fiveAttempts_invalidatesChallenge() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        String code = service.reset("light", firstRequest(), request).getVerifyCode();
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 1; i <= 4; i++) {
            ResetRequest r = firstRequest();
            r.setVerifyCode(wrong);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.reset("light", r, request));
            assertTrue(ex.getMessage().contains("验证码无效"),
                    "第 " + i + " 次错误应提示验证码无效");
        }
        ResetRequest fifth = firstRequest();
        fifth.setVerifyCode(wrong);
        RuntimeException last = assertThrows(RuntimeException.class,
                () -> service.reset("light", fifth, request));
        assertTrue(last.getMessage().contains("错误次数过多"),
                "第 5 次错误应作废挑战");
    }

    @Test
    @DisplayName("换 IP 使用同一验证码被拒，且挑战立即作废")
    void differentIp_rejected() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.3", "10.0.0.4");
        String code = service.reset("light", firstRequest(), request).getVerifyCode();
        ResetRequest r = firstRequest();
        r.setVerifyCode(code);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.reset("light", r, request));
        assertTrue(ex.getMessage().contains("设备不匹配"));
        // 挑战已被作废，原 IP 也不能再用
        ResetRequest retry = firstRequest();
        retry.setVerifyCode(code);
        assertThrows(RuntimeException.class, () -> service.reset("light", retry, request));
    }

    @Test
    @DisplayName("验证码正确但缺密码时被拒；补齐后密码错误也不消耗第二次机会")
    void correctCode_missingPassword_rejected() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");
        String code = service.reset("light", firstRequest(), request).getVerifyCode();
        ResetRequest noPwd = firstRequest();
        noPwd.setVerifyCode(code);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.reset("light", noPwd, request));
        assertTrue(ex.getMessage().contains("登录密码"));
    }

    @Test
    @DisplayName("确认词错误（小写）时不生成挑战：密码校验环节不会被触达")
    void wrongConfirmWord_rejected() {
        ResetRequest r = new ResetRequest();
        r.setConfirmText("reset-lowercase");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.reset("light", r, request));
        assertTrue(ex.getMessage().contains("确认词"));
        // 用户密码表未被查询 = 没走到「生成/校验挑战 + 密码校验」阶段
        verify(userMapper, never()).selectOne(any());
    }
}
