package com.firestar.controller;

import com.firestar.common.JwtUtil;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.realtime.RealtimeGateway;
import com.firestar.realtime.SseEndpointSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * SSE 握手 401 语义回归（2026-10-16 线上事故修复验证，2026-10-19 迁移）。
 *
 * <p><b>事故背景（保留）</b>：旧 /auth/stream 的 401（token 过期 / 被顶号 / jti 不匹配）
 * 被 GlobalExceptionHandler 的 {@code @ExceptionHandler(Exception.class)} 兜底收走 →
 * HTTP 200 + {"code":500} 壳，HTTP 语义丢失，前端 SSE 客户端把 200 当流读、壳里没有
 * event: 行被忽略 → 流关闭 → 2s 重连 → 死循环。
 *
 * <p><b>修复</b>：握手裁决下沉到 {@link SseEndpointSupport#serve} 内部，未认证/过期/被顶号
 * 直接返回 401 ResponseEntity（不再依赖异常处理器兜底）。本测试直接验证 serve 的判定口径，
 * 覆盖原 /auth/stream 的回归点，且不触发 SSE 异步，简洁稳定。
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerStreamTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private UserMapper userMapper;
    @Mock private RealtimeGateway realtimeGateway;

    private SseEndpointSupport support;

    @BeforeEach
    void setUp() {
        support = new SseEndpointSupport(jwtUtil, userMapper, realtimeGateway);
    }

    private HttpServletRequest req(String token) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (token != null) {
            r.addHeader("Authorization", "Bearer " + token);
        }
        return r;
    }

    private void assert401(Object resp, String message) {
        assertTrue(resp instanceof ResponseEntity, "期望 401 ResponseEntity，实际: " + resp);
        ResponseEntity<?> re = (ResponseEntity<?>) resp;
        assertEquals(HttpStatus.UNAUTHORIZED, re.getStatusCode());
        String body = (String) re.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"code\":401"), "body 应含 code:401，实际: " + body);
        assertTrue(body.contains(message), "body 应含文案[" + message + "]，实际: " + body);
    }

    @Test
    void 无token_返回401且壳内code为401() {
        assert401(support.streamResponse(req(null)), "登录已过期，请重新登录");
    }

    @Test
    void 无效token_解析抛异常_返回401() {
        when(jwtUtil.getUsername(anyString())).thenThrow(new RuntimeException("token 格式非法"));
        assert401(support.streamResponse(req("bad-token")), "登录已过期，请重新登录");
    }

    @Test
    void jti不匹配被顶号_返回401且保留顶号文案() {
        when(jwtUtil.getUsername("valid-token")).thenReturn("admin");
        when(jwtUtil.getJti("valid-token")).thenReturn("jti-old");
        User user = new User();
        user.setUsername("admin");
        user.setCurrentJti("jti-new");
        when(userMapper.selectOne(any())).thenReturn(user);
        assert401(support.streamResponse(req("valid-token")),
                "您的账号已在其他设备登录，原会话已失效，请重新登录");
    }

    @Test
    void currentJti为null_未纳入管控_放行返回SseEmitter() {
        when(jwtUtil.getUsername("valid-token")).thenReturn("admin");
        when(jwtUtil.getJti("valid-token")).thenReturn("jti-1");
        User user = new User();
        user.setUsername("admin");
        user.setCurrentJti(null);
        when(userMapper.selectOne(any())).thenReturn(user);
        SseEmitter emitter = Mockito.mock(SseEmitter.class);
        assertSame(emitter, support.serve(req("valid-token"), (u, j) -> emitter));
    }

    @Test
    void 正常握手_返回SseEmitter() {
        when(jwtUtil.getUsername("valid-token")).thenReturn("admin");
        when(jwtUtil.getJti("valid-token")).thenReturn("jti-1");
        User user = new User();
        user.setUsername("admin");
        user.setCurrentJti("jti-1");
        when(userMapper.selectOne(any())).thenReturn(user);
        SseEmitter emitter = Mockito.mock(SseEmitter.class);
        assertSame(emitter, support.serve(req("valid-token"), (u, j) -> emitter));
    }
}
