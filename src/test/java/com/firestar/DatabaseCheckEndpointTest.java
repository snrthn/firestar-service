package com.firestar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自检端点的**访问方式**契约测试。
 *
 * 【背景：两个真实发生的误判】
 *
 * ① 路径末尾多一个 `/`
 *    Spring Boot 3 默认 `setUseTrailingSlashMatch=false`，
 *    `/system/db-check` 与 `/system/db-check/` 是两个不同的映射，带斜杠直接 404：
 *      {"status":404,"error":"Not Found","path":"/system/db-check/"}
 *    对业务接口来说严格是对的，但对**运维探针**是反效果的 ——
 *    手敲 curl / 从文档复制粘贴时多一个斜杠，就会让人误判成"功能没上线"。
 *    所以两个端点都显式声明了带斜杠变体。
 *
 * ② 多写了 /api 前缀
 *    前端 vite.config.ts 的代理是 `rewrite: path => path.replace(/^\/api/, '')`，
 *    **会剥掉** /api。所以前端调 `/api/system/db-check` 到达后端时是 `/system/db-check`。
 *    后端若再声明 @RequestMapping("/api/system") 就成了 `/api/api/system/...`。
 *    开发期因为前端同源，浏览器看不出异常；部署到 Nginx（同样剥离 /api）会直接 404。
 *
 * 这个测试存在的意义：把上述两条路径契约钉死，
 * 防止后来有人觉得"双映射是冗余的"或"加上 /api 更规范"而改回去。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DatabaseCheckEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1. /system/db-check 正常返回 200 + healthy=true")
    void dbCheckReturnsOk() throws Exception {
        mockMvc.perform(get("/system/db-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.tables.businessTableCount").value(25))
                .andExpect(jsonPath("$.tables.expected").value(25))
                .andExpect(jsonPath("$.tables.matched").value(true))
                .andExpect(jsonPath("$.tables.otherTableCount").value(0))
                .andExpect(jsonPath("$.tables.engineTableCount").value(62))
                .andExpect(jsonPath("$.flyway.currentVersion").value("3"))
                .andExpect(jsonPath("$.flyway.appliedCount").value(3))
                .andExpect(jsonPath("$.seedData.dictTypeCount").value(23))
                .andExpect(jsonPath("$.seedData.dictDataCount").value(82))
                .andExpect(jsonPath("$.connection.catalog").value("firestar"))
                .andExpect(jsonPath("$.connection.productName").value("MySQL"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    @DisplayName("2. 末尾带斜杠也必须返回 200（Spring Boot 3 默认不匹配 trailing slash）")
    void trailingSlashAlsoWorks() throws Exception {
        mockMvc.perform(get("/system/db-check/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true));
    }

    @Test
    @DisplayName("3. summary 端点：带/不带斜杠都返回 200，且只含概要字段")
    void summaryWorksWithAndWithoutSlash() throws Exception {
        for (String path : new String[]{"/system/db-check/summary", "/system/db-check/summary/"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.healthy").value(true))
                    .andExpect(jsonPath("$.businessTableCount").value(25))
                    .andExpect(jsonPath("$.flywayVersion").value("3"))
                    .andExpect(jsonPath("$.catalog").value("firestar"));
        }
        // 概要版不该带这些重字段
        mockMvc.perform(get("/system/db-check/summary"))
                .andExpect(jsonPath("$.tables").doesNotExist())
                .andExpect(jsonPath("$.flyway").doesNotExist());
    }

    @Test
    @DisplayName("4. 根路径已登记新端点，便于发现")
    void indexAdvertisesEndpoints() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpoints.dbCheck").value("/system/db-check"))
                .andExpect(jsonPath("$.endpoints.dbCheckSummary").value("/system/db-check/summary"));
    }

    @Test
    @DisplayName("5. 错误路径仍应 404（不能为了宽容把路径匹配放开成通配）")
    void unrelatedPathStill404() throws Exception {
        mockMvc.perform(get("/system/db-checkk")).andExpect(status().isNotFound());
        mockMvc.perform(get("/db-check")).andExpect(status().isNotFound());
    }

    /**
     * 最关键的一条：后端**不能**自己声明 /api 前缀。
     *
     * 代理（vite dev 与生产 Nginx 都会）会剥掉 /api，
     * 所以到达后端的路径是 /system/db-check。
     * 若有人"好心"把 RequestMapping 改成 "/api/system"，
     * 这条断言会立刻失败，而不是等到部署后才发现 404。
     */
    @Test
    @DisplayName("6. 后端路径不带 /api 前缀 —— 代理会剥掉它，写上就双重前缀")
    void backendPathMustNotHaveApiPrefix() throws Exception {
        // 带 /api 的路径必须 404，证明后端确实没声明它
        mockMvc.perform(get("/api/system/db-check")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/system/db-check/summary")).andExpect(status().isNotFound());
        // 不带 /api 的必须通
        mockMvc.perform(get("/system/db-check")).andExpect(status().isOk());
    }
}
