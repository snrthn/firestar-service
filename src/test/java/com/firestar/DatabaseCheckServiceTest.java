package com.firestar;

import com.firestar.service.DatabaseCheckService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据库自检端点的契约测试。
 *
 * 【为什么必须测 —— 一个真实的坑】
 * 第一版按大写前缀 `ACT_` / `FLW_` 过滤引擎表，结果在 Windows 上
 * MySQL 的 lower_case_table_names=1 让 metadata 返回小写 `act_ru_job`，
 * 一个都没匹配上：87 张表被算成业务表，接口稳定误报"业务表应为 25，实际 87"。
 *
 * 而这种错误在 Linux 生产环境（表名大写）上**完全正常**，
 * 只在本地 Windows 开发时暴露 —— 典型的"跨环境行为不一致"，
 * 所以必须在测试里把前缀大小写这条钉死。
 */
@SpringBootTest
@ActiveProfiles("dev")
class DatabaseCheckServiceTest {

    @Autowired
    private DatabaseCheckService service;

    @Autowired
    private DataSource dataSource;

    @SuppressWarnings("unchecked")
    private Map<String, Object> check() {
        return service.check();
    }

    @Test
    @DisplayName("1. dev 库自检通过：36 张业务表、Flyway 38 个版本、种子数据齐全")
    void devDatabaseIsHealthy() {
        Map<String, Object> r = check();
        assertTrue(Boolean.TRUE.equals(r.get("healthy")),
                "dev 库应健康，实际错误：" + r.get("errors"));

        Map<String, Object> tables = (Map<String, Object>) r.get("tables");
        assertEquals(36, tables.get("businessTableCount"), "业务表应为 36 张");
        assertEquals(36, tables.get("expected"));
        assertEquals(Boolean.TRUE, tables.get("matched"));
        assertEquals(0, tables.get("otherTableCount"), "不应存在非业务非引擎的表");

        // V3 之后引擎表应由 Flyway 统一建出来，恰好 62 张。
        // 这条断言的意义：flowable.database-schema-update=false 之后，
        // 引擎表唯一的来源就是 V3__flowable_schema.sql。
        // 一旦 V3 被跳过 / 半途失败，这里会立刻掉到 0 或 7，而不是等到部署后才发现。
        assertEquals(62, tables.get("engineTableCount"), "V3 应建出 62 张引擎表");

        Map<String, Object> flyway = (Map<String, Object>) r.get("flyway");
        assertEquals(38, flyway.get("appliedCount"), "Flyway 应已应用 38 个版本");
        assertEquals("38", flyway.get("currentVersion"));
    }

    @Test
    @DisplayName("2. 引擎表过滤不依赖表名大小写（Windows 小写表名场景）")
    void engineTableFilterIsCaseInsensitive() throws Exception {
        // 先直接从 JDBC 拿原始表名，确认本机返回的是小写还是大写
        Set<String> rawNames = new TreeSet<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) rawNames.add(rs.getString("TABLE_NAME"));
            }
        }
        assertFalse(rawNames.isEmpty(), "应能读到表清单");

        Map<String, Object> tables = (Map<String, Object>) check().get("tables");
        @SuppressWarnings("unchecked")
        List<String> business = (List<String>) tables.get("tables");

        // 引擎表必须被排除干净：业务表清单里不能出现任何引擎表名
        for (String name : business) {
            String lower = name.toLowerCase();
            assertFalse(lower.startsWith("act_") || lower.startsWith("flw_"),
                    "引擎表混进了业务表清单：" + name);
        }
        // 反向断言：如果原始表名是小写，就必须能过滤掉对应数量
        long rawEngine = rawNames.stream()
                .filter(n -> {
                    String l = n.toLowerCase();
                    return l.startsWith("act_") || l.startsWith("flw_");
                })
                .count();
        assertEquals((int) rawEngine, tables.get("engineTableCount"),
                "引擎表数量应与原始清单一致（原始引擎表 " + rawEngine + " 张）");
        assertTrue((int) rawEngine > 0, "本机应存在 Flowable 引擎表");
    }

    @Test
    @DisplayName("3. 种子数据条数与当前迁移（V38）一致")
    void seedDataCountsMatchBaseline() {
        Map<String, Object> seed = (Map<String, Object>) check().get("seedData");
        assertEquals(32L, seed.get("dictTypeCount"));
        assertEquals(130L, seed.get("dictDataCount"));
        assertEquals(13L, seed.get("configCount"));
        assertEquals(7L, seed.get("roleCount"));
        assertEquals(4L, seed.get("roleConflictCount"));
    }

    @Test
    @DisplayName("4. Flyway 版本记录 checksum 非 NULL（防手工伪造版本表）")
    void flywayChecksumsAreReal() {
        Map<String, Object> flyway = (Map<String, Object>) check().get("flyway");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> applied = (List<Map<String, Object>>) flyway.get("applied");
        assertEquals(38, applied.size());
        for (Map<String, Object> row : applied) {
            assertNotNull(row.get("checksum"),
                    row.get("script") + " 的 checksum 为 NULL —— 该版本不是 Flyway 正常执行的");
            assertEquals(Boolean.TRUE, row.get("success"));
        }
        Set<String> scripts = applied.stream()
                .map(r -> (String) r.get("script")).collect(Collectors.toSet());
        // 三个奠基版本必须都在（V1 基线 / V2 字典种子 / V3 引擎表），且脚本名全部符合 Flyway 规范
        assertTrue(scripts.containsAll(Set.of("V1__baseline.sql", "V2__init_dict_data.sql",
                "V3__flowable_schema.sql")), "奠基版本缺失：" + scripts);
        for (String s : scripts) {
            assertTrue(s.matches("V\\d+__.*\\.sql"), "脚本名不符合 Flyway 规范：" + s);
        }
    }

    @Test
    @DisplayName("5. 连接信息不含明文密码")
    void connectionInfoHasNoPlainPassword() {
        Map<String, Object> conn = (Map<String, Object>) check().get("connection");
        String url = String.valueOf(conn.get("url"));
        assertTrue(url.startsWith("jdbc:mysql://"), "URL 应为 jdbc:mysql 开头，实际 " + url);
        assertFalse(url.contains("123456"), "URL 不得包含明文密码");
        assertEquals("MySQL", conn.get("productName"));
        assertEquals("firestar", conn.get("catalog"));
    }
}
