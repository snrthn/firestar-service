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
 *
 * 【2026-11-04 方案 B 改造】期望值不再在本文件里复制第二份。
 * 全部引用 DatabaseCheckService 的 public 常量，或改为动态/下界断言，
 * 避免迁移一推进，测试就冻结在旧版本上误报（本次一次暴露 3 个 Failure）。
 * 测试库为 firestar_test（it profile），由 Flyway 从空库全量重放自建。
 */
@SpringBootTest
@ActiveProfiles("it")
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
    @DisplayName("1. firestar_test 集成测试库自检通过：业务表数、引擎表数、种子数据齐全")
    void devDatabaseIsHealthy() {
        Map<String, Object> r = check();
        assertTrue(Boolean.TRUE.equals(r.get("healthy")),
                "firestar_test 集成测试库应健康，实际错误：" + r.get("errors"));

        Map<String, Object> tables = (Map<String, Object>) r.get("tables");
        // 单一来源：生产与测试共用 DatabaseCheckService 的基线常量
        assertEquals(DatabaseCheckService.EXPECTED_TABLE_COUNT, tables.get("businessTableCount"));
        assertEquals(DatabaseCheckService.EXPECTED_TABLE_COUNT, tables.get("expected"));
        assertEquals(Boolean.TRUE, tables.get("matched"));
        assertEquals(0, tables.get("otherTableCount"), "不应存在非业务非引擎的表");

        // V3 之后引擎表应由 Flyway 统一建出来，恰好 62 张。
        // 这条断言的意义：flowable.database-schema-update=false 之后，
        // 引擎表唯一的来源就是 V3__flowable_schema.sql。
        // 一旦 V3 被跳过 / 半途失败，这里会立刻掉到 0 或 7，而不是等到部署后才发现。
        assertEquals(62, tables.get("engineTableCount"), "V3 应建出 62 张引擎表");

        Map<String, Object> flyway = (Map<String, Object>) r.get("flyway");
        // 不写死「75 个版本」这类会随迁移推进而腐化的数字，只要求完整重放
        assertTrue(((Number) flyway.get("appliedCount")).intValue() >= 100,
                "Flyway 应已重放完整迁移序列，实际 " + flyway.get("appliedCount"));
        assertNotNull(flyway.get("currentVersion"), "应有当前版本号");
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
    @DisplayName("3. 种子数据条数与当前迁移一致（期望值单一来源）")
    void seedDataCountsMatchBaseline() {
        Map<String, Object> seed = (Map<String, Object>) check().get("seedData");
        // 只维护一份：DatabaseCheckService.readSeedData 的 public 常量
        assertEquals(DatabaseCheckService.EXPECTED_DICT_TYPE_COUNT, seed.get("dictTypeCount"));
        assertEquals(DatabaseCheckService.EXPECTED_DICT_DATA_COUNT, seed.get("dictDataCount"));
        assertEquals(DatabaseCheckService.EXPECTED_CONFIG_COUNT, seed.get("configCount"));
        assertEquals(DatabaseCheckService.EXPECTED_ROLE_COUNT, seed.get("roleCount"));
        assertEquals(DatabaseCheckService.EXPECTED_ROLE_CONFLICT_COUNT, seed.get("roleConflictCount"));
    }

    @Test
    @DisplayName("4. Flyway 版本记录 checksum 非 NULL（防手工伪造版本表）")
    void flywayChecksumsAreReal() {
        Map<String, Object> flyway = (Map<String, Object>) check().get("flyway");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> applied = (List<Map<String, Object>>) flyway.get("applied");
        // 与 appliedCount 自洽即可，不再写死「74 个版本」
        assertEquals(((Number) flyway.get("appliedCount")).intValue(), applied.size());
        assertTrue(applied.size() >= 3, "至少应有奠基版本记录");
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
            // 允许小数版本号（测试专用补偿迁移 V26.1 / V27.1 / V74.1）
            assertTrue(s.matches("V[0-9.]+__.*\\.sql"), "脚本名不符合 Flyway 规范：" + s);
        }
    }

    @Test
    @DisplayName("5. 连接信息不含明文密码，且确实连的是集成测试库")
    void connectionInfoHasNoPlainPassword() throws Exception {
        Map<String, Object> conn = (Map<String, Object>) check().get("connection");
        String url = String.valueOf(conn.get("url"));
        assertTrue(url.startsWith("jdbc:mysql://"), "URL 应为 jdbc:mysql 开头，实际 " + url);
        assertFalse(url.contains("password="), "URL 不得携带明文密码参数");
        assertEquals("MySQL", conn.get("productName"));
        // 动态取当前库名，不写死 firestar / firestar_test（CI 换库名也不会误报）
        try (Connection c = dataSource.getConnection()) {
            assertEquals(c.getCatalog(), String.valueOf(conn.get("catalog")),
                    "自检报告里的 catalog 应与实际连接一致");
            assertTrue(url.contains(c.getCatalog()), "URL 应指向当前测试库 " + c.getCatalog());
        }
    }
}
