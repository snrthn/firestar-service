package com.firestar.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 数据库自检服务。
 *
 * 【为什么 actuator 不够用】
 * actuator 的 db 健康检查只回答「连得上吗」，但回答不了「连的是不是对的库、
 * 表建全了没、Flyway 跑到第几版、字典有没有灌进去」——
 * 而后者才是生产环境初始化时真正会出事的地方。
 * 比如连到了一个空库，actuator 依然报 UP，直到第一个业务请求才炸。
 *
 * 【为什么用原生 JDBC 而非 JdbcTemplate / Mapper】
 * 这个检查的**首要目的是诊断数据库本身**。若数据库连接池已耗尽或事务已损坏，
 * 走 MP/MyBatis 链路会跟着一起失败，诊断信息反而丢了。
 * 用 DataSource.getConnection() 拿一条干净连接并显式 close，是最不容易被污染的路径。
 */
@Slf4j
@Service
public class DatabaseCheckService {

    /**
     * V1 基线应该存在的业务表总数（与 docs/建表方案.md 附录 A 一致）。
     *
     * 33 = 原 25 张业务表（V1） + V5 的 sys_token_blacklist（JWT 登出黑名单）
     *      + V10 的 sys_post（岗位管理）
     *      + V19 的 sys_login_log（登录日志，与 sys_audit_log 操作日志区分）
     *      + V25 的 sys_agreement / sys_agreement_version / sys_agreement_consent（P1 协议三表）
     *      + V30 的 sys_notification / sys_notification_template（P2 通知两表）
     *      + V39 的 sys_level（职级管理，P3 LEVEL 节点数据层）。
     *      + V65 的 fin_invoice_allocation（发票↔应收单双向核销明细）。
     * 后续每次 Flyway 新增业务表，此常量必须同步 +1，否则自检误报
     * "业务表数量应为 N，实际 N+1" 导致 /system/db-check 返回 503、
     * start.sh（若以 /system/db-check 作探针）健康轮询永远等不到 200（启动超时）。
     */
    private static final int EXPECTED_TABLE_COUNT = 39;

    /** 本项目业务表前缀 */
    private static final List<String> BUSINESS_TABLE_PREFIXES = Arrays.asList("fin_", "sys_");

    /**
     * 流程引擎表前缀（Flowable），不计入业务表统计。
     *
     * ⚠️ 【必须大小写不敏感】MySQL 在 Windows 上 lower_case_table_names=1，
     * DatabaseMetaData 返回的表名是**小写**（act_ru_job 而非 ACT_RU_JOB）。
     * 按大写前缀过滤在 Windows 上一个都匹配不上，会把 56 张引擎表
     * 误算进业务表，导致表数量检查永远失败。这是实测踩到的。
     */
    private static final List<String> ENGINE_TABLE_PREFIXES =
            Arrays.asList("act_", "flw_");

    private final DataSource dataSource;

    public DatabaseCheckService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行完整自检。
     *
     * 【设计要点】整个方法**不抛异常**，任何一步失败都收集进 errors 列表，
     * 返回结构化报告。理由：诊断接口一旦抛异常就退化成 500，
     * 调用方拿不到"到底哪一步失败的"这个最有价值的信息。
     */
    public Map<String, Object> check() {
        Map<String, Object> report = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        long start = System.currentTimeMillis();

        try (Connection conn = dataSource.getConnection()) {
            // ---- 1. 连接层 ----
            DatabaseMetaData meta = conn.getMetaData();
            Map<String, Object> connInfo = new LinkedHashMap<>();
            connInfo.put("productName", meta.getDatabaseProductName());
            connInfo.put("productVersion", meta.getDatabaseProductVersion());
            connInfo.put("driverName", meta.getDriverName());
            connInfo.put("driverVersion", meta.getDriverVersion());
            connInfo.put("catalog", conn.getCatalog());
            // URL 里的密码在 JDBC 层已被剥离，但保险起见不输出完整 URL
            connInfo.put("url", maskUrl(meta.getURL()));
            connInfo.put("readOnly", conn.isReadOnly());
            connInfo.put("autoCommit", conn.getAutoCommit());
            connInfo.put("transactionIsolation", isolationName(conn.getTransactionIsolation()));
            report.put("connection", connInfo);

            // ---- 2. 表数量 ----
            Map<String, Object> tables = countTables(conn, meta, errors);
            report.put("tables", tables);

            // ---- 3. Flyway 版本 ----
            report.put("flyway", readFlywayVersion(conn, errors));

            // ---- 4. 种子数据 ----
            report.put("seedData", readSeedData(conn, errors));

        } catch (SQLException e) {
            // 连不上 —— 这是最关键的一种失败，直接把异常带回去
            errors.add("获取数据库连接失败: " + e.getMessage());
            report.put("connection", null);
            report.put("fatal", true);
        }

        report.put("errors", errors);
        report.put("healthy", errors.isEmpty());
        report.put("costMs", System.currentTimeMillis() - start);
        report.put("checkedAt", java.time.LocalDateTime.now().toString());
        return report;
    }

    /**
     * 统计表数量并与基线比对。
     *
     * 【为什么用 DatabaseMetaData 而不是 information_schema】
     * metadata 走 JDBC 标准接口，换库也能用；information_schema 是 MySQL 专有的。
     * 顺带能过滤掉 Flowable 引擎表，只关心业务表。
     */
    private Map<String, Object> countTables(Connection conn, DatabaseMetaData meta,
                                            List<String> errors) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> businessTables = new ArrayList<>();
        List<String> otherTables = new ArrayList<>();
        int engineCount = 0;

        try (ResultSet rs = meta.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                // 统一小写后比较，规避 MySQL 的 lower_case_table_names 差异
                String lower = name.toLowerCase();
                if (lower.startsWith("flyway")) continue;
                if (ENGINE_TABLE_PREFIXES.stream().anyMatch(lower::startsWith)) {
                    engineCount++;
                } else if (BUSINESS_TABLE_PREFIXES.stream().anyMatch(lower::startsWith)) {
                    businessTables.add(name);
                } else {
                    // 既不是业务表也不是引擎表 —— 多出来的杂表，必须让人看见，
                    // 悄悄忽略会让"库被污染"这类问题永远查不出来
                    otherTables.add(name);
                }
            }
        }
        Collections.sort(businessTables);
        Collections.sort(otherTables);

        result.put("businessTableCount", businessTables.size());
        result.put("engineTableCount", engineCount);
        result.put("otherTableCount", otherTables.size());
        result.put("expected", EXPECTED_TABLE_COUNT);
        result.put("matched", businessTables.size() == EXPECTED_TABLE_COUNT);
        result.put("tables", businessTables);
        if (!otherTables.isEmpty()) {
            result.put("otherTables", otherTables);
        }

        if (businessTables.size() != EXPECTED_TABLE_COUNT) {
            errors.add(String.format("业务表数量应为 %d，实际 %d —— 可能是 Flyway 未执行或连错库",
                    EXPECTED_TABLE_COUNT, businessTables.size()));
        }
        if (!otherTables.isEmpty()) {
            errors.add("存在非业务、非引擎的表 " + otherTables
                    + " —— 请确认是否有人手工建过表");
        }
        return result;
    }

    /**
     * 读取 Flyway 当前版本。
     *
     * 【为什么这一项不可省】
     * V1/V2 的 checksum 是 Flyway 自己算的。如果有人手工插过版本记录
     * （checksum 为 NULL），下次 migrate 就会 validate 失败。
     * 把版本号和 checksum 都暴露出来，是为了让这类问题在部署阶段就暴露，
     * 而不是等到用户第一次访问时。
     */
    private Map<String, Object> readFlywayVersion(Connection conn, List<String> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> applied = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT version, description, script, checksum, success "
                        + "FROM flyway_schema_history ORDER BY installed_rank");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("version", rs.getString("version"));
                row.put("description", rs.getString("description"));
                row.put("script", rs.getString("script"));
                Object checksum = rs.getObject("checksum");
                row.put("checksum", checksum);
                row.put("success", rs.getBoolean("success"));
                applied.add(row);

                if (checksum == null) {
                    errors.add("Flyway " + rs.getString("version")
                            + " 的 checksum 为 NULL —— 该版本不是由 Flyway 正常执行的，下次 migrate 会校验失败");
                }
            }
        } catch (SQLException e) {
            errors.add("读取 flyway_schema_history 失败（表不存在 = Flyway 从未执行）: " + e.getMessage());
            return result;
        }
        result.put("appliedCount", applied.size());
        result.put("currentVersion", applied.isEmpty() ? null
                : applied.get(applied.size() - 1).get("version"));
        result.put("applied", applied);
        return result;
    }

    /**
     * 读取种子数据条数。
     *
     * 【验证的是「迁移完整执行」而非「表建出来了」】
     * V2 脚本用 INSERT ... ON DUPLICATE KEY UPDATE 保证幂等，
     * 所以只要 appliedCount >= 2，这些数字就该在。
     * 对不上说明 V2 被手工绕过或执行中断。
     *
     * ⚠️ 这些预期值同表数量常量一样需要维护：新增 Flyway 迁移若改动种子数据，
     * 必须同步更新（V16 补 menu_type「外链」字典项 → dictDataCount 82→83，
     * 2026-10-15 探针误报「V2 未完整执行」实为常量未跟上）。
     *
         * V19（文件/日志模块）新增 3 个字典类型（login_status / file_status / storage_type）
         * 与 8 个字典项，故 dictTypeCount 23→26、dictDataCount 83→91。
         * V20（file_biz_type 字典）新增 1 个字典类型与 7 个字典项，故 dictTypeCount 26→27、
         * dictDataCount 91→98（V20 另将 15 行中文 status 归一为 ENABLED，不改变行数）。
         * V21（audit_operation 补 UPLOAD）新增 1 个字典项，dictDataCount 98→99。
        * V22（字典对齐真实数据）净变化：+1 类型（audit_target_type）、
        * permission_action 6→3（-3）、dict_style 3→5（+2）、audit_target_type +6 数据，
        * 故 dictTypeCount 27→28、dictDataCount 99→104（已用真实库对账，见迁移头注释）。
        * V24（file_type 补 AUDIO/VIDEO）新增 2 个字典项，dictDataCount 104→106。
        * V32（问题反馈 + 帮助中心）新增 1 个类型（feedback_status）与 4 个字典项。
        * V34（问题类别）新增 1 个类型（feedback_category）与 7 个字典项。
        * V35（帮助分类）新增 1 个类型（help_category）与 5 个字典项。
        *    → 三笔合计：dictTypeCount 28→31、dictDataCount 106→122。
        * V38（P3 审批工作流 · Step 2 配置节点）新增 1 个字典类型
        * （project_approval_status）与 8 个字典项（project_approval_status 6 项 +
        *  audit_operation 补 SUBMIT/REJECT 2 项），故 dictTypeCount 31→32、
        *  dictDataCount 122→130。
         * V50（发货审批）新增 1 个字典项（shipment_status 补 REJECTED），130→131；
         * V54（签收证据）新增 1 个字典项（file_biz_type 补 shipment_receive_proof），131→132；
         * V56（撤回）新增 1 个字典项（shipment_status 补 CANCELLED），132→133。
         *    → 三笔均未新增字典类型，故 dictTypeCount 保持 32。
         * V59（发票类型）新增 1 个字典类型（invoice_type）与 2 个字典项（NORMAL/SPECIAL），
         *    → dictTypeCount 32→33、dictDataCount 133→135。
         * V62（回款/发票真上传）仅幂等覆盖既有 file_biz_type 项（receipt/invoice 早在 V20 已种），
         *    → 不新增任何字典行，故二者计数均保持 V59 后的值不变。
         * V63（开票状态补枚举）新增 2 个字典项（invoice_status 补 CANCELLED/REJECTED，
         *    后端 InvoiceServiceImpl 早就写入这两个字面量，V2 种子漏种），
         *    → 不新增字典类型，故 dictTypeCount 保持 33、dictDataCount 135→137。
         * V64（收款状态补 CANCELLED）新增 1 个字典项（receipt_status 补 CANCELLED，
         *    撤回动作 2026-10-23 才有，V2 种子未含），
         *    → 不新增字典类型，故 dictTypeCount 保持 33、dictDataCount 137→138。
        * ⚠️ 本类常量已四次因「迁移改了种子数却没同步这里」误报（V19/V20/V22 各一次；
        *    V32+V34+V35 于 2026-10-21 线上一次性暴露，报错文案却指向 V2，排查被带偏）。
        *    今后任何新增字典/种子类的 Flyway 迁移都务必同步本段常量。
     */
    private Map<String, Object> readSeedData(Connection conn, List<String> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dictTypeCount", countOf(conn, "sys_dict_type", 33, errors));
        result.put("dictDataCount", countOf(conn, "sys_dict_data", 138, errors));
        result.put("configCount", countOf(conn, "sys_config", 13, errors));
        result.put("roleCount", countOf(conn, "sys_role", 7, errors));
        result.put("roleConflictCount", countOf(conn, "sys_role_conflict", 4, errors));
        return result;
    }

    private long countOf(Connection conn, String table, long expected, List<String> errors) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            long n = rs.next() ? rs.getLong(1) : -1;
            if (n != expected) {
                // 文案不再写死「V2 未完整执行」：历史四次误报里有三次是新增字典的迁移
                // 没同步本类期望值，原文案把排查方向带偏（2026-10-21 修正）
                errors.add(String.format(
                        "%s 条数应为 %d，实际 %d —— 与种子基线不符：若近期有新增/删除字典或种子的"
                                + " Flyway 迁移，需同步 DatabaseCheckService.readSeedData 的期望值",
                        table, expected, n));
            }
            return n;
        } catch (SQLException e) {
            errors.add("统计 " + table + " 失败: " + e.getMessage());
            return -1;
        }
    }

    /** JDBC URL 里可能带参数，保留主机与库名即可，密码类参数要剥掉 */
    private String maskUrl(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        String base = q > 0 ? url.substring(0, q) : url;
        // 极少数驱动会把密码写进 URL（如 mysql 的 storePassword），这里保守处理
        return base.replaceAll("(?i)(password|pwd)=[^&]*", "$1=***");
    }

    private String isolationName(int level) {
        return switch (level) {
            case Connection.TRANSACTION_NONE -> "NONE";
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
            case Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
            case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            default -> "UNKNOWN(" + level + ")";
        };
    }
}
