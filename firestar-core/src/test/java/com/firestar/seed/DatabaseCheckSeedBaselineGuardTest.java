package com.firestar.seed;

import com.firestar.service.DatabaseCheckService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子基线护栏：把「迁移改了字典/种子行数 → 必须同步 DatabaseCheckService 的期望值」
 * 这条规矩从「口头约定 + 注释提醒」变成**构建期强制**。
 *
 * <p><b>背景（反复复发的事故）</b>：自检按「行数」校验种子数据。迁移往
 * {@code sys_dict_type} / {@code sys_dict_data} 加了行，Java 常量没跟着改，于是
 * {@code /system/db-check} 直接返回 <b>503 Service Unavailable</b>，
 * 前端工作台显示「后端连接失败」，actuator 的 databaseTable 指标 DOWN，
 * 启动自检刷「端口已就绪但就绪探针未通过」——
 * 而报错文案与「字典」毫无关联，排查方向极易被带偏成「后端挂了」。</p>
 *
 * <p>这个坑历史上复发过至少 6 次（V19 / V20 / V22 / V32+V34+V35 / V214），
 * 根因永远是同一个：<b>迁移脚本与 Java 常量分处两地，靠人记着同步</b>。</p>
 *
 * <p><b>本测试强制的三条规则</b>（基线真源见
 * {@code firestar-core/src/main/resources/db/migration/db-check-baseline.properties}）：</p>
 * <ol>
 *   <li><b>基线文件 ≡ Java 常量</b>：db-check-baseline.properties 里 6 个数字
 *       必须逐一等于 {@link DatabaseCheckService} 的同名 public 常量。</li>
 *   <li><b>锚点之后的迁移必须声明基线</b>：凡是动了 sys_dict_type / sys_dict_data /
 *       sys_config / sys_role / sys_role_conflict，或新建 fin_/sys_/ai_ 前缀业务表的迁移，
 *       脚本里必须带一行：<br>
 *       {@code -- db-check-baseline: table=51, dictType=37, dictData=162, config=14, role=9, roleConflict=4}
 *       （填的是<b>改完之后的累计总数</b>，不是增量）</li>
 *   <li><b>最新标记 ≡ 基线文件</b>：版本号最高的那个标记必须与基线文件一致，
 *       也就是「改了字典就必须同时改三处：迁移标记 + 基线文件 + Java 常量」。</li>
 * </ol>
 *
 * <p>纯静态扫描，不连数据库、不需要 MySQL，因此可以进 CI 的纯单测 job。</p>
 */
class DatabaseCheckSeedBaselineGuardTest {

    /** 基线文件（classpath 路径）。 */
    private static final String BASELINE_RESOURCE = "db/migration/db-check-baseline.properties";

    /** 迁移脚本里的基线标记行。 */
    private static final Pattern MARKER_PATTERN = Pattern.compile(
            "--\\s*db-check-baseline:\\s*(.+)", Pattern.CASE_INSENSITIVE);

    /** Flyway 版本号（V214__x.sql / V26.1__x.sql）。 */
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^V([0-9]+(?:\\.[0-9]+)?)__", Pattern.CASE_INSENSITIVE);

    /** 新建业务表（业务表统计口径只认 fin_/sys_/ai_ 三个前缀，见 DatabaseCheckService）。 */
    private static final Pattern CREATE_BUSINESS_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"']?(fin_|sys_|ai_)");

    /** 被基线计数覆盖的表（动到它们的行数就得声明基线）。 */
    private static final String[] COUNTED_TABLES = {
            "sys_dict_type", "sys_dict_data", "sys_config", "sys_role", "sys_role_conflict"
    };

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("1. 基线文件 ≡ DatabaseCheckService 常量（两处永远一致）")
    void baselineFileMatchesJavaConstants() throws Exception {
        Properties baseline = loadBaseline();
        Map<String, Long> expected = javaBaselines();
        for (Map.Entry<String, Long> e : expected.entrySet()) {
            String raw = baseline.getProperty(e.getKey());
            assertNotNull(raw, () -> "基线文件 " + BASELINE_RESOURCE + " 缺少键：" + e.getKey()
                    + "（该键对应 DatabaseCheckService 的同名常量，必须存在）");
            assertEquals(e.getValue(), Long.parseLong(raw.trim()),
                    () -> "基线文件 " + e.getKey() + " = " + raw + "，与 DatabaseCheckService 常量 "
                            + e.getValue() + " 不一致。三处（迁移标记 / 基线文件 / Java 常量）必须同时改。");
        }
    }

    @Test
    @DisplayName("2. 锚点之后任何动种子行数的迁移，都必须声明 db-check-baseline 标记")
    void migrationsAfterBaselineMustDeclareBaseline() throws Exception {
        BigDecimal anchor = baselineVersion();
        List<String> violations = new ArrayList<>();
        for (Path p : collectMigrationSql()) {
            BigDecimal version = versionOf(p);
            if (violatesBaselineRule(version, anchor, readFile(p))) {
                violations.add(p.getFileName() + "（V" + version + "）");
            }
        }
        assertTrue(violations.isEmpty(), () ->
                "以下迁移改了种子基线（字典/配置/角色/业务表），却没声明 db-check-baseline 标记：\n  "
                        + String.join("\n  ", violations)
                        + "\n修法：在脚本里加一行注释（填改完之后的累计总数，不是增量）：\n"
                        + "  -- db-check-baseline: table=.., dictType=.., dictData=.., config=.., role=.., roleConflict=..\n"
                        + "并同步 " + BASELINE_RESOURCE + " 与 DatabaseCheckService 的同名常量（三处一起改）。");
    }

    @Test
    @DisplayName("3. 版本号最高的基线标记 ≡ 基线文件（改了字典必须三处同改）")
    void latestMarkerMatchesBaselineFile() throws Exception {
        Properties baseline = loadBaseline();
        Path latest = null;
        BigDecimal latestVersion = null;
        Map<String, Long> latestMarker = null;

        for (Path p : collectMigrationSql()) {
            BigDecimal version = versionOf(p);
            if (version == null) {
                continue;
            }
            Map<String, Long> marker = findMarker(readFile(p));
            if (marker == null) {
                continue;
            }
            if (latestVersion == null || version.compareTo(latestVersion) > 0) {
                latestVersion = version;
                latest = p;
                latestMarker = marker;
            }
        }

        // 还没有任何迁移带标记 → 说明锚点之后没有动过基线，与基线文件自然一致
        if (latestMarker == null) {
            return;
        }
        // lambda 里要用 latest，必须先转成 effectively final 的副本
        final Path latestFile = latest;

        for (Map.Entry<String, Long> e : javaBaselines().entrySet()) {
            Long inFile = Long.parseLong(baseline.getProperty(e.getKey()).trim());
            Long inMarker = latestMarker.get(e.getKey());
            assertNotNull(inMarker, () -> "迁移 " + latestFile.getFileName()
                    + " 的 db-check-baseline 标记缺少键：" + e.getKey());
            assertEquals(inFile, inMarker, () -> "迁移 " + latestFile.getFileName()
                    + " 声明的基线 " + e.getKey() + "=" + inMarker + " 与基线文件 " + BASELINE_RESOURCE
                    + " 的 " + inFile + " 不一致 —— 三处（迁移标记 / 基线文件 / Java 常量）必须同一个 PR 改完。");
        }
    }

    @Test
    @DisplayName("4. 扫描覆盖度：至少扫到 core 与 biz 两个模块的迁移脚本")
    void scannerCoversAllModules() throws Exception {
        List<Path> files = collectMigrationSql();
        Set<String> modules = new LinkedHashSet<>();
        for (Path p : files) {
            modules.add(extractModule(p));
        }
        assertTrue(modules.contains("firestar-core"),
                "未扫到 firestar-core 的迁移脚本，实际扫到：" + modules);
        assertTrue(modules.contains("firestar-biz"),
                "未扫到 firestar-biz 的迁移脚本，实际扫到：" + modules);
        assertTrue(files.size() >= 100, "扫到的迁移脚本数量异常偏少：" + files.size());
    }

    @Test
    @DisplayName("5. 杀虫剂自检：护栏本身有效（动了字典又没标记，一定会被抓出来）")
    void guardIsEffective() throws Exception {
        // 5.1 标记解析器有效
        Map<String, Long> parsed = parseMarkerBody("table=51, dictType=37, dictData=162");
        assertEquals(37L, parsed.get("dictType"));
        assertEquals(162L, parsed.get("dictData"));

        // 5.2 动字典且没标记 → 必被抓（这是本测试存在的意义）
        String bad = "-- 补一条字典项\n"
                + "INSERT INTO `sys_dict_data` (`type_code`,`dict_label`,`dict_value`) "
                + "VALUES ('x_type','X','x',1,0,'info','ENABLED',0);\n";
        assertTrue(touchesCountedTables(bad), "应识别出该迁移动了 sys_dict_data");
        assertFalse(hasMarker(bad), "该迁移确实没有基线标记");

        BigDecimal anchor = baselineVersion();
        BigDecimal after = anchor.add(BigDecimal.ONE);
        assertTrue(violatesBaselineRule(after, anchor, bad),
                "护栏没抓到「锚点之后动了字典又没声明基线」的迁移 —— 规则失效");
        assertFalse(violatesBaselineRule(anchor, anchor, bad),
                "锚点本身及之前的历史迁移不该被新规则追责");
        // 带上标记后，同样的迁移就合法了
        assertFalse(violatesBaselineRule(after, anchor,
                        "-- db-check-baseline: table=51, dictType=37, dictData=162, config=14, role=9, roleConflict=4\n" + bad),
                "声明了基线标记的迁移不该被判违规");

        // 5.3 注释里提到表名不算「动了表」（否则所有解释性注释都会误报）
        String onlyComment = "-- 本迁移不改 sys_dict_data，只是补了句说明\nSELECT 1;\n";
        assertTrue(onlyComment.contains("sys_dict_data"));
        assertFalse(touchesCountedTables(onlyComment), "注释里出现表名不应被判成动了该表");

        // 5.4 新建业务表要算「动了表」，建引擎表不算
        assertTrue(touchesCountedTables("CREATE TABLE `sys_kb_thing` (`id` BIGINT);"));
        assertFalse(touchesCountedTables("CREATE TABLE `act_ru_task` (`id` BIGINT);"));
    }

    // -------------------------------------------------------------- 基线数据

    /** 基线键 → DatabaseCheckService 的 public 常量（生产与测试只维护这一份真源）。 */
    private static Map<String, Long> javaBaselines() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("table", (long) DatabaseCheckService.EXPECTED_TABLE_COUNT);
        m.put("dictType", DatabaseCheckService.EXPECTED_DICT_TYPE_COUNT);
        m.put("dictData", DatabaseCheckService.EXPECTED_DICT_DATA_COUNT);
        m.put("config", DatabaseCheckService.EXPECTED_CONFIG_COUNT);
        m.put("role", DatabaseCheckService.EXPECTED_ROLE_COUNT);
        m.put("roleConflict", DatabaseCheckService.EXPECTED_ROLE_CONFLICT_COUNT);
        return m;
    }

    private static Properties loadBaseline() throws IOException {
        Properties p = new Properties();
        try (InputStream in = DatabaseCheckSeedBaselineGuardTest.class
                .getClassLoader().getResourceAsStream(BASELINE_RESOURCE)) {
            assertNotNull(in, "基线文件缺失：" + BASELINE_RESOURCE
                    + "（它承载「迁移 → 基线常量」的约定，删了护栏就失效了）");
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static BigDecimal baselineVersion() throws IOException {
        String raw = loadBaseline().getProperty("baselineVersion");
        assertNotNull(raw, "基线文件缺少 baselineVersion（护栏锚点）");
        return new BigDecimal(raw.trim());
    }

    // -------------------------------------------------------------- 迁移扫描

    /**
     * 规则 ②的判定内核：版本高于锚点 + 动了被计数的表 + 没带基线标记 → 违规。
     * 抽成方法是为了能在单测里直接喂假迁移验证「护栏真的会抓」（见用例 5）。
     */
    private static boolean violatesBaselineRule(BigDecimal version, BigDecimal anchor, String rawSql) {
        if (version == null || version.compareTo(anchor) <= 0) {
            return false; // 历史存量：无法追溯补标记
        }
        return touchesCountedTables(rawSql) && !hasMarker(rawSql);
    }

    /** 该迁移是否动了被基线计数的表（增删改都算；新建业务表也算）。 */
    private static boolean touchesCountedTables(String rawSql) {
        String sql = stripComments(rawSql);
        for (String table : COUNTED_TABLES) {
            if (Pattern.compile("(?i)\\b" + table + "\\b").matcher(sql).find()) {
                return true;
            }
        }
        return CREATE_BUSINESS_TABLE.matcher(sql).find();
    }

    /** 去掉 SQL 注释（注释里出现表名不算「动了表」）。 */
    private static String stripComments(String sql) {
        String s = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        return s.replaceAll("(?m)--.*$", " ");
    }

    /** 迁移里是否带基线标记。 */
    private static boolean hasMarker(String rawSql) {
        return MARKER_PATTERN.matcher(rawSql).find();
    }

    /** 取迁移里的基线标记（没有则返回 null）。 */
    private static Map<String, Long> findMarker(String rawSql) {
        Matcher m = MARKER_PATTERN.matcher(rawSql);
        if (!m.find()) {
            return null;
        }
        return parseMarkerBody(m.group(1));
    }

    /** 解析标记体：{@code table=51, dictType=37, ...}。 */
    private static Map<String, Long> parseMarkerBody(String body) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String part : body.split(",")) {
            String kv = part.trim();
            int eq = kv.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = kv.substring(0, eq).trim();
            String val = kv.substring(eq + 1).trim();
            try {
                out.put(key, Long.parseLong(val));
            } catch (NumberFormatException ignored) {
                // 非法数字不参与比较（标记格式写错时由「缺键」断言报错）
            }
        }
        return out;
    }

    private static BigDecimal versionOf(Path p) {
        Matcher m = VERSION_PATTERN.matcher(p.getFileName().toString());
        if (!m.find()) {
            return null;
        }
        return new BigDecimal(m.group(1));
    }

    /** 从完整路径里取最后一个以 firestar- 开头的段作为模块名。 */
    private static String extractModule(Path p) {
        String module = "?";
        for (Path part : p) {
            String s = part.toString();
            if (s.startsWith("firestar-")) {
                module = s;
            }
        }
        return module;
    }

    /**
     * 收集全项目迁移脚本：classpath（jar 内也能扫）+ 文件系统兜底（同级模块）。
     * 只读操作，不触碰数据库。
     */
    private static List<Path> collectMigrationSql() throws IOException {
        Set<Path> out = new LinkedHashSet<>();

        ClassLoader cl = DatabaseCheckSeedBaselineGuardTest.class.getClassLoader();
        Enumeration<URL> urls = cl.getResources("db/migration");
        while (urls.hasMoreElements()) {
            URL u = urls.nextElement();
            if (!"file".equals(u.getProtocol())) {
                continue;
            }
            Path root;
            try {
                root = Paths.get(u.toURI());
            } catch (java.net.URISyntaxException e) {
                continue;
            }
            if (Files.isDirectory(root)) {
                walkSql(root, out);
            }
        }

        Path base = Paths.get("").toAbsolutePath();
        Path parent = base.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            try (Stream<Path> mods = Files.list(parent)) {
                List<Path> dirs = mods.filter(Files::isDirectory).toList();
                for (Path mod : dirs) {
                    Path mig = mod.resolve("src").resolve("main").resolve("resources").resolve("db").resolve("migration");
                    if (Files.isDirectory(mig)) {
                        walkSql(mig, out);
                    }
                }
            }
        }

        List<Path> list = new ArrayList<>(out);
        list.sort(Comparator.comparing(Path::toString));
        return list;
    }

    private static void walkSql(Path root, Set<Path> out) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".sql"))
                    .forEach(out::add);
        }
    }

    private static String readFile(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
