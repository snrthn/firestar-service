package com.firestar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开面（攻击面）契约测试 —— 2026-10-19 安全收口后的「谁能访问什么」。
 *
 * <p>背景：收口前这几类接口在 SecurityConfig 里是 <b>无条件 permitAll</b>，线上同样直接放行：
 * <ol>
 *   <li>{@code /system/db-check} —— 匿名可拿到库名、表名、Flyway 版本、种子数据条数；</li>
 *   <li>{@code /v3/api-docs}、{@code /doc.html}、{@code /swagger-ui/**} —— 匿名可下载
 *       全部接口清单（管理端路径、参数名、鉴权要求，等于一张攻击地图）；</li>
 *   <li>{@code /actuator/health} —— 匿名可探测服务状态。</li>
 * </ol>
 *
 * <p>本类把收口后的口径钉成断言，防止后续有人「为了排查方便」把白名单加回去。
 * 接口文档那一项单独放在 {@link ApiDocsClosedTest}：它需要属性覆盖（关掉 springdoc），
 * 与 dev 环境的上下文不同，混在一个类里会互相污染。
 *
 * <p>为什么只保留 liveness / readiness 匿名：bin/start.sh 与 deploy.sh 用 curl 轮询
 * 它们做启动门禁，request 不带 token；且 {@code management.endpoint.health.show-details=never}
 * 下返回体只有 {@code {"status":"UP"}}，不含任何业务/运维信息。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
class SecurityExposureTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1. /system/db-check 匿名不可达 —— 401（含库名表名，不能公开）")
    void dbCheckIsNotAnonymous() throws Exception {
        mockMvc.perform(get("/system/db-check"))
                // 401 = 未认证（该去登录），不是 403：与 SecurityConfig 的 entryPoint 口径一致
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("2. 登录后即可访问 /system/db-check —— 只认证、不再要求细粒度权限点")
    @WithMockUser(username = "staff", authorities = "ROLE_STAFF")
    void dbCheckNeedsLoginOnly() throws Exception {
        MvcResult result = mockMvc.perform(get("/system/db-check")).andReturn();
        // 断言的是「登录不再被权限层挡」：
        //   - 不能是 401（认证层没放行）
        //   - body 不能是 PermissionInterceptor 的 code=403
        // 不断言 200：库里数据基线不对时接口按设计返回 503，那是业务结论不是权限问题。
        assertNotEquals(401, result.getResponse().getStatus(), "登录后仍被判未认证");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("\"code\":403"), "登录后不该再被细粒度权限点挡住：" + body);
    }

    @Test
    @DisplayName("3. /actuator/health 匿名不可达，但 liveness 必须保持匿名（部署门禁依赖）")
    void actuatorSplitByProbe() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isUnauthorized());

        // 这条一旦变成 401，bin/start.sh 会判「启动超时」→ 部署直接失败。
        // 只断言 liveness：application.properties 只配了这一个健康组，
        // readiness 没有对应分组（实测 404），别按 k8s 习惯臆造一条白名单。
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("4. /ping 与 /error 仍匿名 —— 冒烟探测与错误透传不能被收口误伤")
    void smokeAndErrorStayPublic() throws Exception {
        mockMvc.perform(get("/ping")).andExpect(status().isOk());
        // /error 匿名可达 => 受保护接口报错时状态码能原样透出，不会被伪装成 401
        int errorStatus = mockMvc.perform(get("/error")).andReturn().getResponse().getStatus();
        assertNotEquals(401, errorStatus, "/error 被收进鉴权了：业务报错会被伪装成未登录");
    }
}
