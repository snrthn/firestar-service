package com.firestar.ai.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.internal.parser.PlaceholderReplacingReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移脚本占位符护栏。
 *
 * <p><b>背景（真实事故）</b>：V214__ai_model_type.sql 的注释里写了
 * {@code ai.kek=${AI_KEK}}，导致后端启动失败：</p>
 * <pre>
 * org.flywaydb.core.api.FlywayException: Unable to parse statement in
 *   db/migration/core/V214__ai_model_type.sql at line 11 col 1.
 *   No value provided for placeholder: ${AI_KEK}.
 * </pre>
 *
 * <p><b>根因</b>：Flyway 在解析语句<b>之前</b>先对脚本全文做占位符替换，
 * <b>注释也在替换范围内</b>；而 Spring Boot 只把 {@code spring.flyway.placeholders}
 * 里声明的键喂给 Flyway，{@code application.properties} 里的 {@code ${AI_KEK}}
 * 不在其中 → 替换找不到值 → 解析直接抛异常 → {@code flywayInitializer} bean
 * 建不起来 → 整个 Spring 容器启动失败（表现为"启动超时/启动失败"）。</p>
 *
 * <p><b>为什么必须加护栏</b>：迁移脚本里写一个 {@code ${}} 就足以让整个后端起不来，
 * 而且报错信息（"No value provided for placeholder"）与真实原因（注释里的占位符）
 * 距离很远，排查成本高。本测试把这类错误挡在构建期。</p>
 *
 * <p><b>规则</b>：迁移脚本内禁止出现「美元符号 + 左花括号」形态的占位符；
 * 需要引用环境变量的，一律在 Java 侧用 {@code @Value("${...}")} 读，
 * 不写进 SQL。</p>
 */
class MigrationSqlPlaceholderGuardTest {

    /** Flyway 默认的占位符定界符（Spring Boot 不改这两个）。 */
    private static final String PLACEHOLDER_PREFIX = "${";
    private static final String PLACEHOLDER_SUFFIX = "}";

    @Test
    @DisplayName("1. 所有迁移脚本都不得包含 ${} 占位符")
    void noPlaceholderInMigrationScripts() throws Exception {
        List<Path> files = collectMigrationSql();
        assertFalse(files.isEmpty(), "未扫描到任何迁移脚本，护栏失效（请检查扫描路径）");
        for (Path p : files) {
            String content = readFile(p);
            assertFalse(content.contains(PLACEHOLDER_PREFIX),
                    () -> "迁移脚本 " + p.getFileName() + " 含有 ${...} 占位符。"
                            + "Flyway 会连注释一起做占位符替换，未在 spring.flyway.placeholders 声明的键"
                            + "会导致解析期抛 FlywayException，整个后端启动失败。请改为在 Java 侧读取环境变量。");
        }
    }

    @Test
    @DisplayName("2. 用 Flyway 真实占位符替换器跑一遍所有迁移脚本，均不抛异常")
    void flywayPlaceholderReplacementPasses() throws Exception {
        for (Path p : collectMigrationSql()) {
            String content = readFile(p);
            assertDoesNotThrow(() -> readAll(new PlaceholderReplacingReader(
                            PLACEHOLDER_PREFIX, PLACEHOLDER_SUFFIX, Map.of(), new StringReader(content))),
                    () -> "迁移脚本 " + p.getFileName() + " 经 Flyway 占位符替换失败。");
        }
    }

    @Test
    @DisplayName("3. 杀虫剂自检：护栏本身有效（${AI_KEK} 必然触发 FlywayException）")
    void guardIsEffective() {
        String poisoned = "-- 注释里的 ${AI_KEK}\nSELECT 1;\n";
        assertThrows(FlywayException.class,
                () -> readAll(new PlaceholderReplacingReader(
                        PLACEHOLDER_PREFIX, PLACEHOLDER_SUFFIX, Map.of(), new StringReader(poisoned))),
                "护栏失效：Flyway 竟然没有对未声明的 ${AI_KEK} 抛异常");
    }

    @Test
    @DisplayName("4. 扫描覆盖度：至少要扫到 core 与 biz 两个模块的迁移脚本")
    void scannerCoversAllModules() throws Exception {
        List<Path> files = collectMigrationSql();
        Set<String> modules = new LinkedHashSet<>();
        for (Path p : files) {
            modules.add(extractModule(p));
        }
        assertTrue(modules.contains("firestar-core"),
                "未扫到 firestar-core 的迁移脚本，实际扫到: " + modules);
        assertTrue(modules.contains("firestar-biz"),
                "未扫到 firestar-biz 的迁移脚本，实际扫到: " + modules);
        assertTrue(files.size() >= 100, "扫到的迁移脚本数量异常偏少: " + files.size());
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

    // ---------------------------------------------------------------- helpers

    /**
     * 收集全项目迁移脚本：classpath（jar 内也能扫）+ 文件系统兜底（同级模块）。
     * 只读操作，不触碰数据库。
     */
    private static List<Path> collectMigrationSql() throws IOException {
        Set<Path> out = new LinkedHashSet<>();

        // 1) classpath：db/migration 及子目录
        ClassLoader cl = MigrationSqlPlaceholderGuardTest.class.getClassLoader();
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

        // 2) 文件系统兜底：覆盖 core 测试 classpath 里没有的其它模块（biz/server）
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

    private static String readAll(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = reader.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
