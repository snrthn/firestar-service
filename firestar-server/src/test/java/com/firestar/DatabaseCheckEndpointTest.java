package com.firestar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自检端点的**访问方式**契约测试。
 *
 * 【背景：三个真实发生的误判】
 *
 * ① 路径末尾多一个 `/`
 *    Spring Boot 3 默认 `setUseTrailingSlashMatch=false`，
 *    `/system/db-check` 与 `/system/db-check/` 是两个不同的映射，带斜杠直接 404：
 *      {"status":404,"error":"Not Found","path":"/system/db-check/"}
 *    对业务接口来说严格是对的，但对**运维探针**是反效果的 ——
 *    手敲 curl / 从文档复制粘贴时多一个斜杠，就会让人误判成"功能没上线"。
 *    所以端点显式声明了带斜杠变体。
 *
 * ② 多写了 /api 前缀
 *    前端 vite.config.ts 的代理是 `rewrite: path => path.replace(/^\/api/, '')`，
 *    **会剥掉** /api。所以前端调 `/api/system/db-check` 到达后端时是 `/system/db-check`。
 *    后端若再声明 @RequestMapping("/api/system") 就成了 `/api/api/system/...`。
 *    开发期因为前端同源，浏览器看不出异常；部署到 Nginx（同样剥离 /api）会直接 404。
 *
 * ③ 入口太多导致"该调哪个"
 *    曾经同时存在 /ping、/actuator/health、/actuator/info、/db-check、/db-check/summary
 *    五个探测入口。前端要检测时根本不知道该用哪个 —— 而/actuator/health
 *    只报连接池/磁盘这类粗粒度状态，出问题时看不出"连的是不是对的库"。
 *    现已统一为：前端健康检测**只调 dbCheck**。
 *
 * 这个测试存在的意义：把上述三条契约钉死，
 * 防止后来有人觉得"双映射是冗余的""再加个 summary 更轻量"
 * "actuator 那个更标准"而改回去。
 *
 * 【2026-10-19 安全收口后本类的前提变了】
 * /system/db-check 不再是匿名接口：必须带有效 JWT（**登录即可，不再要求细粒度权限点**）。
 * 所以本类统一以管理员身份调用，匿名访问必须 401 的那一半契约搬到 SecurityExposureTest 里断言 ——
 * 两个类职责分开：这里测「路径/映射契约」，那边测「谁能访问」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@WithMockUser(username = "admin", authorities = "ROLE_ADMIN")
class DatabaseCheckEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("1. /system/db-check 正常返回 200 + healthy=true（断契约，不钉具体表数）")
    void dbCheckReturnsOk() throws Exception {
        MvcResult result = mockMvc.perform(get("/system/db-check"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = readJson(result);
        assertTrue(body.path("healthy").asBoolean(), "自检未通过：" + body.path("errors"));

        JsonNode tables = body.path("tables");
        // 【2026-10-19 改法说明】原断言把 businessTableCount=25 / engineTableCount=62 /
        // flyway=3 / dict 条数全写成字面量，等于给数据库拍了一张快照：
        // V4 加了 RBAC 三张表、后续再加迁移，测试就永久变红，而业务本身完全正常。
        // 现在只钉「不变量」：业务表数 == 代码基线、没有基线外的游离表、迁移跑过。
        assertEquals(tables.path("expected").asInt(), tables.path("businessTableCount").asInt(),
                "业务表数与 EXPECTED_TABLE_COUNT 不一致");
        assertTrue(tables.path("matched").asBoolean(), "表清单与基线不匹配");
        assertEquals(0, tables.path("otherTableCount").asInt(),
                "库里有基线之外的表：" + tables.path("otherTables"));
        assertTrue(body.path("errors").isEmpty(), "自检报告有错误项");
        // 方案 B 核心不变量：集成测试绝不能连回 dev 库 firestar
        String catalog = body.path("connection").path("catalog").asText();
        assertTrue(!catalog.isBlank() && !"firestar".equals(catalog),
                "集成测试必须连独立测试库，绝不能连回 dev 库 firestar，实际=" + catalog);
        assertTrue(body.path("flyway").path("appliedCount").asInt() >= 3, "Flyway 迁移一个都没应用");
    }

    @Test
    @DisplayName("2. 末尾带斜杠也必须返回 200（Spring Boot 3 默认不匹配 trailing slash）")
    void trailingSlashAlsoWorks() throws Exception {
        mockMvc.perform(get("/system/db-check/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true));
    }

    @Test
    @DisplayName("3. summary 端点已移除 —— 前端统一用 db-check，不再有第二个入口")
    void summaryEndpointIsGone() throws Exception {
        // 带斜杠 / 不带斜杠、以及它的子路径，都必须是 404。
        // 这条断言防止有人"觉得 summary 更轻量"又加回来。
        for (String path : new String[]{"/system/db-check/summary",
                "/system/db-check/summary/",
                "/system/db-check/summary/anything"}) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
        // 根路径的端点清单里也不能再有它
        mockMvc.perform(get("/"))
                .andExpect(jsonPath("$.endpoints.dbCheckSummary").doesNotExist())
                .andExpect(jsonPath("$.endpoints.dbCheck").value("/system/db-check"))
                // actuator 那两个也从清单里去掉了
                .andExpect(jsonPath("$.endpoints.health").doesNotExist())
                .andExpect(jsonPath("$.endpoints.info").doesNotExist())
                .andExpect(jsonPath("$.endpoints.ping").value("/ping"));
    }

    @Test
    @DisplayName("4. 根路径已登记新端点，便于发现")
    void indexAdvertisesEndpoints() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpoints.dbCheck").value("/system/db-check"));
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
