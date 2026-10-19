package com.firestar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口文档在生产口径下必须对匿名关闭。
 *
 * <p>用属性覆盖（{@code springdoc.api-docs.enabled=false}）模拟 prod / pre 的配置，
 * 断言三件事：
 * <ol>
 *   <li>文档路径不在匿名白名单里 —— 匿名请求拿 401，而不是 200；</li>
 *   <li>即使有人绕过 SecurityConfig（比如自己加回白名单），springdoc 本身也已关闭，
 *       拿到的也是 404 而不是接口清单；</li>
 *   <li>不影响正常的业务鉴权链路。</li>
 * </ol>
 *
 * <p>【为什么值得单独一个上下文】SecurityConfig 在构造时读这个开关决定白名单，
 * 属于「启动期决策」，只能换一个 ApplicationContext 才测得准；
 * 和 dev（文档打开）的用例混在一个类里会互相污染缓存。
 */
@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ApiDocsClosedTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1. /v3/api-docs 匿名 401 —— 接口清单不再对公网开放")
    void apiDocsRequiresAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. /doc.html 与 swagger-ui 匿名 401 —— Swagger 页面不再公开")
    void swaggerUiRequiresAuth() throws Exception {
        mockMvc.perform(get("/doc.html")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    }
}
