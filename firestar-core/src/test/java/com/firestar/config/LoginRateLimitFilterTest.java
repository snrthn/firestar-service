package com.firestar.config;

import com.firestar.exception.LoginRateLimitException;
import com.firestar.service.LoginProtectService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * LoginRateLimitFilter 纯单元回归（不连库、不启 Spring）。
 * 验证：限流触发 → 429 且不放行；正常 → 放行；非登录路径 → 不触发 acquire。
 */
@ExtendWith(MockitoExtension.class)
class LoginRateLimitFilterTest {

    @Mock
    private LoginProtectService protect;

    @Mock
    private FilterChain chain;

    private LoginRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new LoginRateLimitFilter(protect);
    }

    @Test
    @DisplayName("L2：限流触发返回 429 且拦截请求")
    void blocksWhenRateLimited() throws Exception {
        doThrow(new LoginRateLimitException("登录尝试过于频繁")).when(protect).acquire(anyString());

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
        req.addHeader("X-Forwarded-For", "203.0.113.7");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertEquals(429, res.getStatus());
        assertTrue(res.getContentAsString().contains("429"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("L2：未超限正常放行")
    void passesThroughWhenAllowed() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("L2：非登录路径不触发限流")
    void ignoresNonLoginPaths() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/auth/profile");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(any(), any());
        verify(protect, never()).acquire(anyString());
    }
}
