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
     * 运行期业务表总数（与 docs/建表方案.md 附录 A 一致）。
     *
     * 41 = 原 25 张业务表（V1）
     *      + V5  sys_token_blacklist（JWT 登出黑名单）
     *      + V10 sys_post（岗位管理）
     *      + V19 sys_login_log（登录日志，与 sys_audit_log 操作日志区分）
     *      + V25 sys_agreement / sys_agreement_version / sys_agreement_consent（P1 协议三表）
     *      + V30 sys_notification / sys_notification_template（P2 通知两表）
     *      + V32 sys_feedback / sys_feedback_message / sys_help_article（反馈 + 帮助中心）
     *      + V39 sys_level（职级管理，P3）
     *      + V65 fin_invoice_allocation（发票↔应收单核销明细）
     *      + V67 fin_return_line（退货明细行，按行退）
     *      + V68 fin_customer（客户主表，V68 引入客户维度唯一事实源）
     *      + V71 fin_goods（货物主表，V71 引入货物维度唯一事实源；fin_item 同时挂 goods_id）
     * 后续每次 Flyway 新增业务表，此常量必须同步 +1，否则自检误报
     * "业务表数量应为 N，实际 N+1" 导致 /system/db-check 返回 503、
     * start.sh（若以 /system/db-check 作探针）健康轮询永远等不到 200（启动超时）。
     *
     * 计数变更账：V67 新增 fin_return_line，38→39；V68 新增 fin_customer，39→40；
     *            V71 新增 fin_goods，40→41（V32 三表此前漏记，已补正）；
     *            V99 新增 sys_model_config（AI 模型配置），41→42；
     *            V100 新增 ai_conversations / ai_messages / ai_tool_calls（AI 会话三表），
     *            纳入业务前缀后 42→45（本次漏调曾触发 db-check 503「手工建表」误报）；
     *            V105 新增 sys_ai_prompt（AI 提示词管理），45→46。
     */
    public static final int EXPECTED_TABLE_COUNT = 46;

    /**
     * 种子数据条数基线（与 readSeedData 一一对应）。
     *
     * 【为什么 public】这些值曾经在 server 集成测试里被复制成第二份硬编码，
     * 迁移一推进，测试就冻结在旧版本上误报（2026-11-04 一次暴露 3 个 Failure）。
     * 现在测试直接引用本类常量，生产与测试只维护一份。新增改动种子行数的迁移时，
     * 改这里即可，不要再去测试文件里改数字。
     */
    public static final long EXPECTED_DICT_TYPE_COUNT = 35L;
    public static final long EXPECTED_DICT_DATA_COUNT = 148L;
    public static final long EXPECTED_CONFIG_COUNT = 14L;
    public static final long EXPECTED_ROLE_COUNT = 7L;
    public static final long EXPECTED_ROLE_CONFLICT_COUNT = 4L;

    /**
     * 本项目业务表前缀。
     * ⚠️ 新增 AI 模块（V100 会话/消息/工具调用三表 ai_*）后必须把 ai_ 纳入业务前缀，
     * 否则这三张表会落入 otherTables 被误报「存在非业务、非引擎的表 / 手工建表」
     * （实测 2026-11-02 触发 /system/db-check 返回 503）。
     */
    public static final List<String> BUSINESS_TABLE_PREFIXES =
            Collections.unmodifiableList(Arrays.asList("fin_", "sys_", "ai_"));

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
         * V67（退货全栈地基）字典 seed 写了 3 行 INSERT，但**净新增只有 2 行**：
         *    ① receipt_type 补 REFUND  —— V2 未种，真新增（+1）；
         *    ② return_approval_status 补 CANCELLED —— V2 未种，真新增（+1）；
         *    ③ receivable_source 'FROM_RETURN' —— ⚠️ **V2 baseline 第 75 行早已种过**
         *       （当时 label 叫「退货红冲」），V67 同一 (type_code, dict_value) 再插一次被
         *       ON DUPLICATE KEY UPDATE 命中 → 只把 label 改成「退货生成」，**未新增行**（+0）。
         *    → dictTypeCount 保持 33、dictDataCount 138→140（实测值 140 已验）。
         * V69（audit_target_type 补齐 INVOICE/APPROVAL/RECEIPT/PROJECT 4 项，对齐
         *    @Auditable 真实落库的 10 个目标类型）→ 不新增字典类型，
         *    dictDataCount 140→144（dictTypeCount 保持 33）。
         * V70（core_business_status_styles）仅为字典项 `style` 列配色，纯 UPDATE、
         *   无任何 INSERT/DELETE/CREATE，**不新增/不删除任何行**，故所有计数常量均不变
         *   （dictTypeCount 保持 33、dictDataCount 保持 144；config/role 等亦不变）。
         * V73（发货审批状态字典 shipment_approval_status）新增 1 个字典类型与 6 个字典项
         *   （DRAFT/PENDING_APPROVAL/APPROVING/REJECTED/APPROVED/CANCELLED，镜像 project_approval_status），
         *   → dictTypeCount 33→34、dictDataCount 144→150（已用真实库对账：实际 34/150）。
         * V74（shipment_approval_status 状态归一）仅为 status 中文'启用'→'ENABLED' 的纯 UPDATE，
         *   无任何 INSERT/DELETE，计数不变（dictTypeCount 保持 34、dictDataCount 保持 150）。
         *   此类「只改已有行属性」的迁移无需同步本段常量，仅当计数基线与实际不符时排查。
         * V75（审批流状态字典通用化）净变化：
         *   删 4 个审批类型（project_approval_status / shipment_approval_status /
         *   return_approval_status / invoice_status，共 6+6+9+5=26 项）+ 删 receipt_status 4 项
         *   （CASHIER_APPROVING/RECEIVED/PARTIAL_WRITEOFF/FULL_WRITEOFF）；
         *   建 approval_status（1 类型 / 6 项）与 receipt_business_status（1 类型 / 1 项）。
         *   receipt_status 仅保留审批骨架（PENDING_APPROVAL/REJECTED/CANCELLED），类型不删、项剩 3。
         *   → 类型 34 - 4 + 2 = 32；数据 150 - 26 - 4 + 7 = 127。
         * V78（收款/红冲款项类型字典拆分）净变化：
         *   新增 1 个字典类型（receipt_red_type，承载 REFUND 退货退款 + RED 红冲）；
         *   receipt_type 收敛为蓝字{正常收款, 预收款}（删 OVERPAY 多收款、REFUND 迁出），
         *   REFUND 由 receipt_type 迁至 receipt_red_type（行数不增）、新增 RED（+1）、删 OVERPAY（-1）
         *   → 字典项净变化 0；仅类型 +1。
         *   → dictTypeCount 32→33、dictDataCount 保持 127（实际 33/127）。
         *    ⚠️ 计数口径提示：本类按「实际行数」校验，而 V67 迁移注释曾按「INSERT 语句数」
         *       记 +3——凡迁移里出现 ON DUPLICATE KEY UPDATE，行数增量 ≠ 语句行数，
         *       必须以 SELECT COUNT(*) 实测为准，不能数 INSERT。
         * V83（工作台系统状态开关）新增 1 个配置种子（sys_config: dashboard_status_visible，
         *   默认 false、仅调试用）→ configCount 13→14。
         * V84（立项签约合同附件）仅向既有字典类型 file_biz_type 追加 1 个字典项（contract 合同），
         *   不新增字典类型，故 dictTypeCount 保持 33、dictDataCount 127→128
         *   （2026-10-30 虎哥拍板新增合同上传位，当时漏同步本期望值才触发误报）。
 * V95（发票流程重构：业务单号 + 购方六要素）向既有字典类型 approval_status 追加 2 个字典项
 *   （SUBMITTED 待开票 / ISSUED 已开票，撑起 DRAFT→SUBMITTED→ISSUED 三级状态机），
 *   不新增字典类型，故 dictTypeCount 保持 33、dictDataCount 128→130。
 *   （2026-10-31 服务起来后首页探针报「应为 128 实际 130」，又是迁移动了字典没同步本段——
 *    注释里立的规矩自己没守住，第五次了。）
 * V98（发货三部曲：承运商 + 运单号 + 附件）净变化：
 *   新增 1 个字典类型（logistics_carrier）+ 12 个字典项
 *   （logistics_carrier 10 项 + file_biz_type 补 shipment_dispatch 1 项
 *    + audit_target_type 补 SHIPMENT 1 项）
 *   → dictTypeCount 33→34、dictDataCount 130→142（已用真实库对账）。
 *   此迁移刻意把期望值同步写进 readSeedData，杜绝第六次误报。
 * V104（AI 供应商字典 ai_provider）净变化：
 *   新增 1 个字典类型（ai_provider）+ 4 个字典项（coze/openai/deepseek/ollama，
 *   供【模型配置】供应商下拉 / 标签颜色，替代前端硬编码）。
 *   → dictTypeCount 34→35、dictDataCount 142→146（V105~V107 均不改字典行数）。
 *   已同步 readSeedData 期望值（35/146），补本次首屏探针「应为 34/142 实际 35/146」误报。
 * V201（补齐 audit_target_type 的 AI_PROMPT / AI_MODEL_CONFIG）净变化：
 *   新增 2 个字典项，不新增字典类型 → dictTypeCount 保持 35、dictDataCount 146→148。
 *   本轮同时落地 AuditDictConsistencyTest（代码注解 ↔ 字典双向差集，已接进云效门禁），
 *   只数数量的时代结束：漏补字典会直接让 CI fail，漏改本常量会让 bin/test.sh 全量测试 fail。
        * ⚠️ 本类常量已四次因「迁移改了种子数却没同步这里」误报（V19/V20/V22 各一次；
        *    V32+V34+V35 于 2026-10-21 线上一次性暴露，报错文案却指向 V2，排查被带偏）。
        *    今后任何新增字典/种子类的 Flyway 迁移都务必同步本段常量。
     */
    private Map<String, Object> readSeedData(Connection conn, List<String> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dictTypeCount", countOf(conn, "sys_dict_type", EXPECTED_DICT_TYPE_COUNT, errors));
        result.put("dictDataCount", countOf(conn, "sys_dict_data", EXPECTED_DICT_DATA_COUNT, errors));
        result.put("configCount", countOf(conn, "sys_config", EXPECTED_CONFIG_COUNT, errors));
        result.put("roleCount", countOf(conn, "sys_role", EXPECTED_ROLE_COUNT, errors));
        result.put("roleConflictCount", countOf(conn, "sys_role_conflict", EXPECTED_ROLE_CONFLICT_COUNT, errors));
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
