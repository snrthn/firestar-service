package com.firestar;

import com.firestar.dto.InitRequest;
import com.firestar.service.InitResetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0：系统初始化 / 重置向导。
 * ⚠️ 安全边界：本测试【绝不触发真实重置】。reset(light/full) 在 full 模式下会执行
 * Flyway clean()+migrate() 清空整库，绝不能在测试里真调用。
 * 因此只覆盖：① 只读的 getStatus() / precheck()；② 无 @RequirePerm("system:reset") 权限时
 * reset 被 AOP 拦截抛 AccessDeniedException（验证权限防护前置，不进入清库逻辑）。
 */
@SpringBootTest
class InitResetFlowTest {

    @Autowired
    private InitResetService initResetService;

    @Test
    @DisplayName("P0-只读：getStatus 返回初始化状态（不写库）")
    void getStatus_readonly() {
        var status = initResetService.getStatus();
        assertNotNull(status);
        // 至少能拿到 initialized 布尔字段
        assertNotNull(status.getInitialized());
    }

    @Test
    @DisplayName("P0-只读：precheck 返回环境检查项列表（不写库）")
    void precheck_readonly() {
        var items = initResetService.precheck();
        assertNotNull(items);
    }

    @Test
    @DisplayName("P0-防护：无 system:reset 权限调用 reset 被拒绝（AccessDenied，不执行清库）")
    void reset_withoutPermission_rejected() {
        // 设置一个「无权限」的匿名身份上下文，确保 AOP @RequirePerm 拦截
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("guest", null, java.util.List.of()));
        InitRequest req = new InitRequest();
        assertThrows(AccessDeniedException.class,
                () -> initResetService.reset("light", req, null));
    }
}
