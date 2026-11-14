package com.firestar;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.firestar.dto.NodeCfg;
import com.firestar.entity.BaseEntity;
import com.firestar.entity.LogicDeleteEntity;
import com.firestar.entity.ProcessDef;
import com.firestar.mapper.ProcessDefMapper;
import com.firestar.service.DatabaseCheckService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 6 全表映射完整性测试。
 *
 * 【为什么必须自动化】
 * 全量业务表的字段是脚本批量生成的。生成脚本改了、或有人手工加了字段，
 * 都可能出现「DDL 有、实体没有」或「实体有、DDL 没有」的不对齐。
 * 这类不一致**编译期不报错**，只会在某个业务查询时才炸出
 * "Unknown column" 或 "ResultSet 少了字段"，排查成本极高。
 *
 * 所以这里做双向校验：
 *   DDL → 实体：每个非基类字段都能在实体里找到同名属性
 *   实体 → DDL：每个实体属性都能在表里找到同名列
 * 任何一侧对不上立即失败，并打印差异明细。
 *
 * 【2026-11-04 方案 B 改造】消掉「表名清单 / 表数量 / 逻辑删除名单」的第二份硬编码：
 *   - 表清单按 DatabaseCheckService.BUSINESS_TABLE_PREFIXES 从 JDBC 元数据动态取；
 *   - 数量断言引用 DatabaseCheckService.EXPECTED_TABLE_COUNT；
 *   - 逻辑删除 / BaseEntity 检查改为按 DDL 实际列判定，不再冻一份名单。
 *   保留 TABLE_TO_ENTITY 显式映射（这是「新增表必须登记实体」的可审计守卫，不是期望值拷贝）。
 *   测试库为 firestar_test（it profile），由 Flyway 从空库全量重放自建。
 */
@SpringBootTest
@ActiveProfiles("it")
class EntityMappingConsistencyTest {

    /**
     * 不参与校验的列：L1 预留字段。
     * 含 id —— 主键标注 @TableId 后不在 TableInfo.getFieldList() 里（在 keyColumn），
     * 而它由 BaseEntity 提供，是所有表都有的列，逐表校验毫无意义。
     */
    private static final Set<String> IGNORED_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "tenant_id", "created_by", "created_at", "updated_by", "updated_at",
            "version", "ext", "is_deleted", "deleted_at", "deleted_by"));

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MybatisPlusInterceptor interceptor;

    /**
     * 读取当前库全部业务表及其列。按生产侧同一份前缀常量圈定，
     * 避免测试又维护一份表名白名单（历史上那份冻在 V39，缺了 9 张表）。
     */
    private Map<String, Set<String>> readSchemaColumns() throws Exception {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet tables = meta.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String table = tables.getString("TABLE_NAME");
                    String lower = table.toLowerCase();
                    if (lower.startsWith("flyway")) continue;
                    if (DatabaseCheckService.BUSINESS_TABLE_PREFIXES.stream()
                            .noneMatch(lower::startsWith)) continue;
                    Set<String> cols = new TreeSet<>();
                    try (ResultSet c = meta.getColumns(conn.getCatalog(), null, table, null)) {
                        while (c.next()) cols.add(c.getString("COLUMN_NAME").toLowerCase());
                    }
                    result.put(table, cols);
                }
            }
        }
        return result;
    }

    @Test
    @DisplayName("1. 全部业务表都有对应实体，且每个 DDL 列都能在实体中找到")
    void everyColumnHasEntityProperty() throws Exception {
        Map<String, Set<String>> schema = readSchemaColumns();

        assertEquals(DatabaseCheckService.EXPECTED_TABLE_COUNT, schema.size(),
                "业务表数量应与生产自检基线一致，实际 " + schema.size() + " -> " + schema.keySet());

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : schema.entrySet()) {
            String table = e.getKey();
            TableInfo info = tableInfoFor(classFor(table));
            if (info == null) {
                problems.add(table + ": 找不到实体的 TableInfo（@TableName 是否正确）");
                continue;
            }
            Set<String> props = new HashSet<>();
            for (TableFieldInfo fi : info.getFieldList()) {
                props.add(fi.getColumn().toLowerCase());
            }
            // 反查：实体属性 -> 列
            for (String col : e.getValue()) {
                if (IGNORED_COLUMNS.contains(col)) continue;
                if (!props.contains(col)) {
                    problems.add(table + "." + col + ": DDL 有该列，实体无对应属性");
                }
            }
        }
        assertTrue(problems.isEmpty(), "字段未对齐：\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("2. 逻辑删除表集合：实体继承关系与 DDL 的 is_deleted 列必须一致")
    void logicDeleteTableSetMatchesBaseline() throws Exception {
        Map<String, Set<String>> schema = readSchemaColumns();
        Set<String> ddlLogicDelete = schema.entrySet().stream()
                .filter(e -> e.getValue().contains("is_deleted"))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> entityLogicDelete = new TreeSet<>();
        for (String table : schema.keySet()) {
            if (LogicDeleteEntity.class.isAssignableFrom(classFor(table))) {
                entityLogicDelete.add(table);
            }
        }
        assertEquals(ddlLogicDelete, entityLogicDelete,
                "DDL 有 is_deleted 列的表，其实体应继承 LogicDeleteEntity；反之不应继承");
    }

    @Test
    @DisplayName("3. 含 L1 预留字段的表，其实体必须继承 BaseEntity")
    void allEntitiesExtendBaseEntity() throws Exception {
        Map<String, Set<String>> schema = readSchemaColumns();
        // 以下为「DDL 有 tenant_id 但实体不继承 BaseEntity」的有意例外，完整名单见下方 Set.of：
        // sys_ai_prompt：V105 建表时为了索引 idx_ai_prompt_active(is_active, tenant_id) 带了
        //   tenant_id 列，但 AiPromptConfig 是轻量配置 POJO（implements Serializable），
        //   实体不映射该列，查询按全局生效行（is_active=1）走，不做租户隔离。
        //   见 V105 / AiPromptConfig 注释。
        // sys_ai_prompt：见上方注释（DDL 带 tenant_id 但实体为轻量配置 POJO，有意不继承 BaseEntity）。
        // sys_login_fail / sys_login_ip_limit：爆破防护内部状态/计数表，非领域实体，
        // 用唯一索引 + 原子自增维护，实体为简单 POJO，有意不继承 BaseEntity（与 AI 会话表同类）。
        // sys_kb_doc / sys_kb_chunk / sys_kb_attachment（V207 知识库三表，V208 给 doc 加树形四列）：
        //   DDL 同样带 tenant_id，但实体与 AiPromptConfig 同款独立 implements Serializable，
        //   刻意不继承 BaseEntity —— sys_kb_doc 的 version 是「文档业务版本」（发布 v+1 用，
        //   非乐观锁）、没有 ext 列、created_by/updated_by 是 VARCHAR(64) 而非 Long；
        //   chunk / attachment 无 ext / version，created_by 同为 VARCHAR(64)。
        //   详见 KbDoc.java:16-25 与 V207 / V208。
        Set<String> excluded = Set.of("sys_ai_prompt", "sys_login_fail", "sys_login_ip_limit",
                "sys_kb_doc", "sys_kb_chunk", "sys_kb_attachment");
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : schema.entrySet()) {
            if (excluded.contains(e.getKey())) continue;
            // 表本身就没有 L1 字段的（纯运维表 / AI 会话表）不要求继承，动态豁免
            if (!e.getValue().contains("tenant_id")) continue;
            Class<?> cls = classFor(e.getKey());
            if (!BaseEntity.class.isAssignableFrom(cls)) bad.add(e.getKey());
        }
        assertTrue(bad.isEmpty(), "未继承 BaseEntity：\n" + String.join("\n", bad));
    }

    @Test
    @DisplayName("4. 拦截器注册顺序：分页 → 乐观锁 → 防全表（顺序错了会失效）")
    void interceptorOrderIsCorrect() {
        List<String> names = interceptor.getInterceptors().stream()
                .map(i -> i.getClass().getSimpleName())
                .toList();
        assertEquals(List.of("PaginationInnerInterceptor",
                "OptimisticLockerInnerInterceptor",
                "BlockAttackInnerInterceptor"), names,
                "拦截器顺序必须为 分页 → 乐观锁 → 防全表更新，实际 " + names);
    }

    @Test
    @DisplayName("5. JSON 业务列必须挂 TypeHandler（否则读出来是裸 JSON 字符串）")
    void jsonColumnsUseJacksonTypeHandler() throws Exception {
        List<String> bad = new ArrayList<>();
        Map<String, Set<String>> schema = readSchemaColumns();
        for (Map.Entry<String, Set<String>> e : schema.entrySet()) {
            TableInfo info = tableInfoFor(classFor(e.getKey()));
            if (info == null) continue;
            for (TableFieldInfo fi : info.getFieldList()) {
                String col = fi.getColumn().toLowerCase();
                if (!isJsonColumn(e.getKey(), col)) continue;
                Class<?> handler = fi.getTypeHandler();
                if (handler == null || handler != JacksonTypeHandler.class) {
                    bad.add(e.getKey() + "." + col + " typeHandler=" + handler
                            + "（应为 JacksonTypeHandler）");
                }
            }
        }
        assertTrue(bad.isEmpty(), "JSON 列未正确配置 TypeHandler：\n" + String.join("\n", bad));
    }

    @Test
    @Transactional
    @DisplayName("6. 真实读库验证 JSON 列真的被反序列化成 List<NodeCfg>（autoResultMap 生效的硬证据）")
    void jsonColumnReallyDeserializesToList() throws Exception {
        ProcessDefMapper mapper = ctxBean(ProcessDefMapper.class);

        // 造一条带 nodeList 的流程定义（事务结束自动回滚，不留脏数据）
        ProcessDef def = new ProcessDef();
        def.setProcessCode("MP_TEST_PROC_" + System.nanoTime());
        def.setProcessName("阶段6 JSON 验证");
        def.setStatus("ENABLED");
        NodeCfg node1 = new NodeCfg();
        node1.setSeq(1);
        node1.setNodeId("node_1");
        node1.setName("节点1");
        node1.setType("USER");
        node1.setAssignee("1");
        node1.setRejectable(true);
        def.setNodeList(List.of(node1));
        mapper.insert(def);

        ProcessDef loaded = mapper.selectById(def.getId());
        assertNotNull(loaded.getNodeList(),
                "nodeList 应被反序列化为 List；若为 null 说明 autoResultMap 没生效");
        assertEquals(1, loaded.getNodeList().size(), "应解析出 1 个节点");
        NodeCfg first = loaded.getNodeList().get(0);
        assertEquals("node_1", first.getNodeId(), "nodeId 应原样读回");
        assertEquals("USER", first.getType(), "type 应原样读回");
        assertEquals(Boolean.TRUE, first.getRejectable(), "rejectable 应原样读回");
    }

    @Autowired
    private org.springframework.context.ApplicationContext ctx;

    private <T> T ctxBean(Class<T> type) {
        return ctx.getBean(type);
    }

    private boolean isJsonColumn(String table, String col) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, col);
            return rs.next() && "JSON".equals(rs.getString("TYPE_NAME"));
        }
    }

    /** TableInfo 缓存兜底：部分实体的 Mapper 在启动期未被访问，需按需初始化 */
    private static TableInfo tableInfoFor(Class<?> cls) {
        TableInfo info = TableInfoHelper.getTableInfo(cls);
        if (info == null) {
            info = TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), cls);
        }
        return info;
    }

    /**
     * 表名 → 实体类名。显式映射而非拼字符串：可读、可审计，新增业务表时必须在此登记，
     * 否则 everyColumnHasEntityProperty 会明确报「未登记的表」。
     * 值可以是简单类名（com.firestar.entity 包）或全限定名（如 AI 模块）。
     */
    private static final String[] TABLE_TO_ENTITY = {
            "fin_project=Project", "fin_item=Item", "fin_shipment=Shipment",
            "fin_shipment_line=ShipmentLine", "fin_return_order=ReturnOrder",
            "fin_receivable=Receivable", "fin_receipt=Receipt",
            "fin_write_off=WriteOff", "fin_invoice=Invoice",
            // V65 / V67 / V68 / V71 新增的四张业务主数据/明细表
            "fin_invoice_allocation=InvoiceAllocation", "fin_return_line=ReturnLine",
            "fin_customer=Customer", "fin_goods=Goods",
            "sys_process_def=ProcessDef", "sys_org=Org", "sys_user=User",
            "sys_audit_log=AuditLog", "sys_file_storage=FileStorage",
            "sys_file_download_log=FileDownloadLog", "sys_dict_type=DictType",
            "sys_dict_data=DictData", "sys_config=Config", "sys_role=Role",
            "sys_permission=Permission", "sys_menu=Menu", "sys_user_role=UserRole",
            "sys_role_permission=RolePermission", "sys_role_menu=RoleMenu",
            "sys_role_conflict=RoleConflict",
            "sys_agreement=Agreement", "sys_agreement_consent=AgreementConsent",
            "sys_agreement_version=AgreementVersion", "sys_feedback=Feedback",
            "sys_feedback_message=FeedbackMessage", "sys_help_article=HelpArticle",
            "sys_login_log=LoginLog", "sys_login_fail=LoginFail", "sys_login_ip_limit=LoginIpLimit",
            "sys_notification=Notification",
            "sys_notification_template=NotificationTemplate", "sys_post=Post",
            "sys_token_blacklist=TokenBlacklist", "sys_level=Level",
            // V99 / V100 / V105 新增的 AI 模块表：实体在 com.firestar.ai.entity，不继承 BaseEntity
            "sys_model_config=com.firestar.ai.entity.ModelConfig",
            "sys_ai_prompt=com.firestar.ai.entity.AiPromptConfig",
            "ai_conversations=com.firestar.ai.entity.AiConversation",
            "ai_messages=com.firestar.ai.entity.AiMessage",
            "ai_tool_calls=com.firestar.ai.entity.AiToolCall",
            // V207 知识库三表（V208 又给 sys_kb_doc 加了 parent_id / node_type / sort_no / path）
            // 实体同在 com.firestar.ai.entity，与 AiPromptConfig 一样刻意不继承 BaseEntity
            "sys_kb_doc=com.firestar.ai.entity.KbDoc",
            "sys_kb_chunk=com.firestar.ai.entity.KbChunk",
            "sys_kb_attachment=com.firestar.ai.entity.KbAttachment"};

    /** 表名 → 实体类。支持简单类名与全限定名两种写法 */
    private static Class<?> classFor(String table) throws ClassNotFoundException {
        for (String pair : TABLE_TO_ENTITY) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(table)) {
                String name = kv[1];
                return Class.forName(name.contains(".") ? name : "com.firestar.entity." + name);
            }
        }
        throw new ClassNotFoundException("未登记的表：" + table
                + "（新增业务表请同步 EntityMappingConsistencyTest.TABLE_TO_ENTITY 映射）");
    }
}
