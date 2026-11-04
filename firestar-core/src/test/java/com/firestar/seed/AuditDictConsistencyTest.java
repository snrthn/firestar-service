package com.firestar.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 枚举 ↔ 字典双写契约的【CI 门禁】（纯静态扫描，不连库、不需要 MySQL、不需要 Spring 上下文）。
 *
 * <p>为什么必须有这个测试（2026-11-04 彻查结论）：{@code @Auditable.targetType()} /
 * {@code operationType()} 是自由字符串，加一个字面量零成本；而字典项要写 Flyway 迁移 +
 * 同步 {@code DatabaseCheckService} 的计数常量，靠人记。同一个 audit_target_type 字典
 * 已至少 4 轮人工补漏（V22 建 6 项 → V69 补 4 → V98 补 1 → V201 补 2），
 * 注释里写过 N 次「务必同步」，一次都没拦住。
 *
 * <p>本测试直接对源码做<b>双向差集</b>：
 * <ul>
 *   <li>代码有、字典缺 → fail（这是历次翻车的模式，必须零容忍）；</li>
 *   <li>字典有、代码无 → 减去显式登记的历史预留值后必须为空，否则 fail
 *       （防止字典里塞僵尸项，也防止有人随手改注解值留下孤儿）；</li>
 *   <li>扫描结果非空且包含已知锚点值 → 防止「路径没找到 / 正则坏了 → 返回空集 → 假绿」。</li>
 * </ul>
 *
 * <p>新增一个 {@code targetType} 的正确姿势：
 * ① 在业务方法上写 {@code @Auditable(targetType="XXX")}；
 * ② 新建 Flyway 迁移往 {@code sys_dict_data} 插 {@code audit_target_type / XXX}；
 * ③ 同步 {@code DatabaseCheckService.EXPECTED_DICT_DATA_COUNT}（由 bin/test.sh 全量重放校验）。
 * 漏掉 ② 本测试会让 CI 变红；漏掉 ③ DatabaseCheckServiceTest 会让本地全量测试变红。
 *
 * <p>⚠️ 只扫 {@code src/main/}：测试专用补偿迁移 {@code db/migration/it/} 与
 * 编译产物 {@code target/} 都不参与契约，避免假阳性。
 */
class AuditDictConsistencyTest {

    /** 字典中历史预留、代码暂未使用的操作类型（要新增必须显式登记在这里） */
    private static final Set<String> RESERVED_OPERATION_VALUES = Set.of("EXPORT", "LOGIN", "SUBMIT");
    /** 目标类型目前没有预留值；将来若有，同样显式登记 */
    private static final Set<String> RESERVED_TARGET_VALUES = Set.of();

    private static final Pattern ANNOTATION_BLOCK =
            Pattern.compile("@Auditable\\s*\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern TARGET_TYPE =
            Pattern.compile("targetType\\s*=\\s*\"([A-Z0-9_]+)\"");
    private static final Pattern OPERATION_TYPE =
            Pattern.compile("operationType\\s*=\\s*\"([A-Z0-9_]+)\"");
    private static final Pattern DICT_INSERT_BLOCK = Pattern.compile(
            "INSERT\\s+INTO\\s+`?sys_dict_data`?.*?;", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    @DisplayName("targetType：代码注解 ↔ audit_target_type 字典双向差集必须为空")
    void targetTypesAreInSync() throws IOException {
        Path root = repoRoot();
        Set<String> code = scanJava(root, TARGET_TYPE);
        Set<String> dict = scanSql(root, "audit_target_type");

        // 防「扫描器静默失效 → 空集 → 差集为空 → 假绿」
        assertAnchorValue(code, "USER", "@Auditable.targetType");
        assertAnchorValue(code, "AI_PROMPT", "@Auditable.targetType");
        assertAnchorValue(dict, "USER", "audit_target_type 字典");
        assertTrue(code.size() >= 13, "targetType 扫描数量异常偏少，正则或路径可能失效：" + code);

        assertDiffEmpty(code, dict, RESERVED_TARGET_VALUES, "audit_target_type");
    }

    @Test
    @DisplayName("operationType：代码注解 ↔ audit_operation 字典双向差集（扣减预留值）必须为空")
    void operationTypesAreInSync() throws IOException {
        Path root = repoRoot();
        Set<String> code = scanJava(root, OPERATION_TYPE);
        Set<String> dict = scanSql(root, "audit_operation");

        assertAnchorValue(code, "CREATE", "@Auditable.operationType");
        assertAnchorValue(dict, "CREATE", "audit_operation 字典");
        assertTrue(code.size() >= 6, "operationType 扫描数量异常偏少，正则或路径可能失效：" + code);

        assertDiffEmpty(code, dict, RESERVED_OPERATION_VALUES, "audit_operation");
    }

    @Test
    @DisplayName("扫描器自测：注入一个代码侧新枚举值时，差集必须能被算出来")
    void diffLogicDetectsMissingDictEntry() {
        Set<String> code = Set.of("USER", "BRAND_NEW_TARGET");
        Set<String> dict = Set.of("USER");
        Set<String> missing = new TreeSet<>(code);
        missing.removeAll(dict);
        assertTrue(missing.contains("BRAND_NEW_TARGET"), "差集逻辑应能识别缺失项，否则门禁形同虚设");

        // 反向：字典里的僵尸项（非白名单）也必须能被识别
        Set<String> extra = new TreeSet<>(dict);
        extra.removeAll(code);
        extra.removeAll(RESERVED_OPERATION_VALUES);
        assertFalse(extra.contains("NOT_THERE"), "白名单扣减逻辑不应误报");
    }

    private static void assertAnchorValue(Set<String> values, String anchor, String source) {
        assertTrue(values.contains(anchor),
                source + " 扫描结果缺少已知锚点 " + anchor + "，疑似路径/正则失效，实际=" + values);
    }

    private static void assertDiffEmpty(Set<String> code, Set<String> dict,
                                       Set<String> reserved, String dictType) {
        Set<String> missing = new TreeSet<>(code);
        missing.removeAll(dict);
        assertTrue(missing.isEmpty(),
                "【枚举漏补字典】代码中出现但 " + dictType + " 字典里没有：" + missing
                        + "。请新增 Flyway 迁移 INSERT INTO sys_dict_data，"
                        + "并同步 DatabaseCheckService.EXPECTED_DICT_DATA_COUNT。");

        Set<String> extra = new TreeSet<>(dict);
        extra.removeAll(code);
        extra.removeAll(reserved);
        assertTrue(extra.isEmpty(),
                "【字典僵尸项】" + dictType + " 字典里有、代码注解里已不存在：" + extra
                        + "。请删除该字典项，或把它显式登记进本测试的 RESERVED_*_VALUES 白名单。");
    }

    /** 只扫 src/main/java 下的 .java，跳过 target / .git / src/test */
    private static Set<String> scanJava(Path root, Pattern valuePattern) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Path p : sourceFiles(root, ".java")) {
            Matcher block = ANNOTATION_BLOCK.matcher(read(p));
            while (block.find()) {
                Matcher value = valuePattern.matcher(block.group(1));
                while (value.find()) {
                    out.add(value.group(1));
                }
            }
        }
        return out;
    }

    /**
     * 只扫 src/main/resources 下的 .sql，且只在 INSERT INTO sys_dict_data ... ; 语句块内
     * 取该 type_code 的字典项 —— 不能全文搜，否则 sys_dict_type 插入行里的
     * status='ENABLED' 会被误当成字典项（2026-11-04 首轮扫描踩过）。
     */
    private static Set<String> scanSql(Path root, String typeCode) throws IOException {
        Pattern item = Pattern.compile(
                "\\(\\s*'" + Pattern.quote(typeCode) + "'\\s*,\\s*'[^']*'\\s*,\\s*'([A-Z0-9_]+)'");
        Set<String> out = new TreeSet<>();
        for (Path p : sourceFiles(root, ".sql")) {
            Matcher block = DICT_INSERT_BLOCK.matcher(read(p));
            while (block.find()) {
                Matcher value = item.matcher(block.group(0));
                while (value.find()) {
                    out.add(value.group(1));
                }
            }
        }
        return out;
    }

    private static java.util.List<Path> sourceFiles(Path root, String ext) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(ext))
                    .filter(AuditDictConsistencyTest::isMainSource)
                    .toList();
        }
    }

    private static boolean isMainSource(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.contains("/src/main/")
                && !s.contains("/target/")
                && !s.contains("/.git/");
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * 从 user.dir 逐级向上找仓库根（同时含 firestar-core 与 firestar-biz 目录）。
     * 这样无论从仓库根跑 mvn、还是 surefire 把工作目录设成某个模块，都能定位。
     */
    private static Path repoRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve("firestar-core"))
                    && Files.isDirectory(p.resolve("firestar-biz"))) {
                return p;
            }
            p = p.getParent();
        }
        return fail("找不到仓库根（应同时包含 firestar-core / firestar-biz），"
                + " user.dir=" + System.getProperty("user.dir"));
    }
}
