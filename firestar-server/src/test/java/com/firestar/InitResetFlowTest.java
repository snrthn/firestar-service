package com.firestar;

import com.firestar.service.InitResetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0：系统初始化 / 重置向导。
 * ⚠️ 安全边界：本测试【绝不触发真实重置】。reset(full) 会执行 Flyway clean()+migrate()
 * 清空整库，绝不能在测试里真调用。
 *
 * 【2026-11-04 方案 B 改造】
 * ① getStatus：独立测试库由 Flyway 从空库全量重放，V2 种子 sys_initialized=false；
 *    dev 库是 true，所以不能再断言 true —— 改成断言「测试库保持未初始化」，
 *    顺带守住「测试库没被手工初始化过」这条干净性不变量。
 * ② 权限防护：@RequirePerm 挂在 Controller，由 PermissionInterceptor（MVC 层）拦截，
 *    Service 直调根本没有 AOP 校验，期望 AccessDeniedException 从来就不成立。
 *    改走 MockMvc 打端点，断言项目约定的 HTTP 200 + Result{code=403}。
 *    即使拦截器意外放行，请求体不带 password 也会被 Service 的前置密码校验挡下，
 *    不会进入清库逻辑（双重保险）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
class InitResetFlowTest {

    @Autowired
    private InitResetService initResetService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("P0-只读：getStatus 返回初始化状态（不写库）")
    void getStatus_readonly() {
        var status = initResetService.getStatus();
        assertNotNull(status);
        assertFalse(status.isInitialized(),
                "独立测试库从空库重放后 sys_initialized 种子应为 false；若为 true 说明测试库被手工初始化过");
    }

    @Test
    @DisplayName("P0-只读：precheck 返回环境检查项列表（不写库）")
    void precheck_readonly() {
        var items = initResetService.precheck();
        assertNotNull(items);
    }

    @Test
    @WithMockUser(username = "guest")
    @DisplayName("P0-防护：无 system:reset 权限 POST /system/reset/light 被拦截器拒绝（code=403）")
    void reset_withoutPermission_rejected() throws Exception {
        mockMvc.perform(post("/system/reset/light")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }
}
