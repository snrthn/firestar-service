package com.firestar.config;

import com.firestar.common.RequirePerm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校验 @RequirePerm 的 {pathVar} 动态权限码（import:{key} 场景）。 */
class PermissionInterceptorTest {

    @RequirePerm("import:{key}")
    static class DummyController {
        public void submit(String key) {
        }
    }

    private final PermissionInterceptor interceptor = new PermissionInterceptor();

    private static MockHttpServletRequest request(String key) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/import/" + key + "/submit");
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("key", key));
        return req;
    }

    private static HandlerMethod handler() throws Exception {
        Method m = DummyController.class.getMethod("submit", String.class);
        return new HandlerMethod(new DummyController(), m);
    }

    private static void login(String... permCodes) {
        List<GrantedAuthority> authorities = java.util.Arrays.stream(permCodes)
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(
                        JwtAuthenticationFilter.PERM_AUTHORITY_PREFIX + p))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "x", authorities));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("动态权限码：只有 import:customer 时，导发票必须被拒")
    void rejectsWrongKey() throws Exception {
        login("import:customer");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("invoice_opening"), resp, handler()));
        assertTrue(resp.getContentAsString().contains("403"));
    }

    @Test
    @DisplayName("动态权限码：持有 import:invoice_opening 时放行")
    void allowsMatchingKey() throws Exception {
        login("import:invoice_opening");
        assertTrue(interceptor.preHandle(request("invoice_opening"), new MockHttpServletResponse(), handler()));
    }

    @Test
    @DisplayName("ROLE_ADMIN 仍然放行（保险网不变）")
    void adminBypass() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "x",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertTrue(interceptor.preHandle(request("invoice_opening"), new MockHttpServletResponse(), handler()));
    }
}
