package com.firestar;

import com.firestar.dto.LoginRequest;
import com.firestar.dto.LoginResponse;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.service.AuthService;
import com.firestar.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0：登录业务流（JWT 签发 / 单点登录 jti 轮换 / 登出拉黑）。
 * 集成测试，连 dev 库。@BeforeEach 插入专属测试用户，@AfterEach 清理，互不污染。
 * 注意：测试用户字段按 User 实体内省假设（username/password/status/delFlag），
 * 若本地表结构有额外必填，按报错在 setUp 补字段即可。
 */
@SpringBootTest
class AuthServiceFlowTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JwtUtil jwtUtil;

    private static final String TEST_USER = "auth_flow_test_" + System.nanoTime();
    private static final String TEST_PWD = "Auth@123456";
    private Long userId;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setUsername(TEST_USER);
        u.setPassword(new BCryptPasswordEncoder().encode(TEST_PWD));
        u.setNickname("auth-flow");
        u.setStatus(1);
        u.setDelFlag(0);
        userMapper.insert(u);
        userId = u.getId();
    }

    @AfterEach
    void tearDown() {
        if (userId != null) {
            userMapper.deleteById(userId);
        }
    }

    private LoginRequest req() {
        LoginRequest r = new LoginRequest();
        r.setUsername(TEST_USER);
        r.setPassword(TEST_PWD);
        return r;
    }

    @Test
    @DisplayName("P0-登录：正确账号密码返回非空 JWT")
    void login_success_returnsToken() {
        LoginResponse resp = authService.login(req(), "127.0.0.1", "unittest");
        assertNotNull(resp);
        assertNotNull(resp.getToken());
        assertFalse(resp.getToken().isBlank());
    }

    @Test
    @DisplayName("P0-单点登录：同账号二次登录生成新 jti，旧会话被顶替（current_jti 被覆盖）")
    void sso_rotatesCurrentJti_secondInvalidatesFirst() {
        LoginResponse r1 = authService.login(req(), "127.0.0.1", "u1");
        LoginResponse r2 = authService.login(req(), "127.0.0.1", "u2");
        String jti1 = jwtUtil.getJti(r1.getToken());
        String jti2 = jwtUtil.getJti(r2.getToken());
        assertNotEquals(jti1, jti2, "两次登录应产生不同 jti");

        User fresh = userMapper.selectById(userId);
        assertEquals(jti2, fresh.getCurrentJti(),
                "登录即轮换 current_jti，DB 中应为最新会话 jti，旧 token 的 jti 不再匹配");
    }

    @Test
    @DisplayName("P0-登出：token 对应会话被清（current_jti 置空，旧 token 无法再匹配）")
    void logout_clearsCurrentJti() {
        LoginResponse r = authService.login(req(), "127.0.0.1", "u");
        assertNotNull(jwtUtil.getJti(r.getToken()));
        authService.logout(r.getToken());
        User fresh = userMapper.selectById(userId);
        assertNull(fresh.getCurrentJti(), "登出应清空当前会话标识，使该 token 即便未过期也不再匹配 current_jti");
    }

    @Test
    @DisplayName("P0-登录失败：错误密码抛认证异常")
    void login_wrongPassword_throws() {
        LoginRequest bad = req();
        bad.setPassword("wrong-password");
        assertThrows(RuntimeException.class, () -> authService.login(bad, "127.0.0.1", "u"));
    }
}
