package com.firestar.controller;

import com.firestar.common.JwtUtil;
import com.firestar.common.Result;
import com.firestar.entity.User;
import com.firestar.exception.GlobalExceptionHandler;
import com.firestar.mapper.UserMapper;
import com.firestar.realtime.RealtimeGateway;
import com.firestar.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /auth/stream 的 401 语义回归（2026-10-16 线上事故修复验证）。
 *
 * <p><b>事故背景</b>：stream() 抛出的 ResponseStatusException(401)（token 过期 /
 * 被顶号 / jti 不匹配）是 RuntimeException 子类，会被 GlobalExceptionHandler 的
 * {@code @ExceptionHandler(Exception.class)} 兜底收走 → HTTP 200 + {"code":500} 壳，
 * HTTP 语义丢失。前端 SSE 客户端把 200 当流读、壳里没有 event: 行被忽略 →
 * 流关闭 → 2s 重连 → 死循环，表现为「/auth/stream 一直不停的触发且返回 500 壳」。
 *
 * <p><b>修复</b>：GlobalExceptionHandler 增加 ResponseStatusException 专用 handler，
 * 用 ResponseEntity 保留原状态码。本测试同时用「旧兜底 advice」做对照实验，
 * 证明旧代码确实产出 200+500 壳（而非推测）。
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerStreamTest {

    @Mock private AuthService authService;
    @Mock private JwtUtil jwtUtil;
    @Mock private UserMapper userMapper;
    @Mock private RealtimeGateway realtimeGateway;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(authService, jwtUtil, userMapper, realtimeGateway);
    }

    /** 修复后的形态：挂真实的 GlobalExceptionHandler */
    private MockMvc fixedMockMvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 复现事故：只挂「兜底 Exception」handler —— 修复前 GlobalExceptionHandler
     * 没有 ResponseStatusException 分支时的实际行为。
     */
    @RestControllerAdvice
    static class LegacyFallbackAdvice {
        @ExceptionHandler(Exception.class)
        public Result<Void> handle(Exception e) {
            return Result.error(500, "服务器内部错误");
        }
    }

    @Test
    void 无token_修复后_HTTP401且壳内code为401() throws Exception {
        fixedMockMvc().perform(get("/auth/stream"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已过期，请重新登录"));
    }

    @Test
    void 对照实验_修复前兜底handler把401吞成200加500壳() throws Exception {
        MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new LegacyFallbackAdvice())
                .build()
                .perform(get("/auth/stream"))
                // HTTP 语义丢失：401 变成 200，前端 res.ok=true 把它当 SSE 流读
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }

    @Test
    void 无效token_修复后_HTTP401() throws Exception {
        when(jwtUtil.getUsername(anyString())).thenThrow(new RuntimeException("token 格式非法"));
        fixedMockMvc().perform(get("/auth/stream").header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已过期，请重新登录"));
    }

    @Test
    void jti不匹配被顶号_修复后_HTTP401且保留顶号文案() throws Exception {
        when(jwtUtil.getUsername("valid-token")).thenReturn("admin");
        when(jwtUtil.getJti("valid-token")).thenReturn("jti-old");
        User user = new User();
        user.setUsername("admin");
        user.setCurrentJti("jti-new");
        when(userMapper.selectOne(any())).thenReturn(user);

        fixedMockMvc()
                .perform(get("/auth/stream").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(
                        "您的账号已在其他设备登录，原会话已失效，请重新登录"));
    }

    @Test
    void 正常握手_修复后仍然返回200() throws Exception {
        when(jwtUtil.getUsername("valid-token")).thenReturn("admin");
        when(jwtUtil.getJti("valid-token")).thenReturn("jti-1");
        User user = new User();
        user.setUsername("admin");
        user.setCurrentJti("jti-1");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(realtimeGateway.connect("admin", "jti-1"))
                .thenReturn(Mockito.mock(SseEmitter.class));

        fixedMockMvc()
                .perform(get("/auth/stream").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());
    }
}
