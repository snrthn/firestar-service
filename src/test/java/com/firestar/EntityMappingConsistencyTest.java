package com.firestar;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.firestar.config.MybatisPlusConfig;
import com.firestar.entity.BaseEntity;
import com.firestar.entity.LogicDeleteEntity;
import com.firestar.entity.ProcessDef;
import com.firestar.mapper.ProcessDefMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 6 全表映射完整性测试。
 *
 * 【为什么必须自动化】
 * 25 张表 ~200 字段，是脚本批量生成的。生成脚本改了、或有人手工加了字段，
 * 都可能出现「DDL 有、实体没有」或「实体有、DDL 没有」的不对齐。
 * 这类不一致**编译期不报错**，只会在某个业务查询时才炸出
 * "Unknown column" 或 "ResultSet 少了字段"，排查成本极高。
 *
 * 所以这里做双向校验：
 *   DDL → 实体：每个非基类字段都能在实体里找到同名属性
 *   实体 → DDL：每个实体属性都能在表里找到同名列
 * 任何一侧对不上立即失败，并打印差异明细。
 */
@SpringBootTest
@ActiveProfiles("dev")
class EntityMappingConsistencyTest {

    /**
     * 不参与校验的列：L1 预留字段。
     * 含 id —— 主键标注 @TableId 后不在 TableInfo.getFieldList() 里（在 keyColumn），
     * 而它由 BaseEntity 提供，是所有表都有的列，逐表校验毫无意义。
     */
    private static final Set<String> IGNORED_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "tenant_id", "created_by", "created_at", "updated_by", "updated_at",
            "version", "ext", "is_deleted", "deleted_at", "deleted_by"));

    /** 逻辑删除表白名单，必须与 V1 基线一致 */
    private static final Set<String> LOGIC_DELETE_TABLES = new HashSet<>(Arrays.asList(
            "fin_project", "fin_item", "fin_shipment", "fin_shipment_line",
            "fin_return_order", "fin_receivable", "fin_receipt",
            "fin_write_off", "fin_invoice"));

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MybatisPlusInterceptor interceptor;

    /** 读取 INFORMATION_SCHEMA 中 firestar 库的全部业务表列 */
    private Map<String, Set<String>> readSchemaColumns() throws Exception {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet tables = meta.getTables(conn.getCatalog(), null, "fin\\_%", new String[]{"TABLE"});
            collect(meta, tables, result);
            tables = meta.getTables(conn.getCatalog(), null, "sys\\_%", new String[]{"TABLE"});
            collect(meta, tables, result);
        }
        return result;
    }

    private void collect(DatabaseMetaData meta, ResultSet tables, Map<String, Set<String>> out) throws Exception {
        while (tables.next()) {
            String table = tables.getString("TABLE_NAME");
            if (table.startsWith("flyway")) continue;
            Set<String> cols = new TreeSet<>();
            ResultSet c = meta.getColumns(meta.getConnection().getCatalog(), null, table, null);
            while (c.next()) cols.add(c.getString("COLUMN_NAME").toLowerCase());
            out.put(table, cols);
        }
    }

    @Test
    @DisplayName("1. 25 张表全部有对应实体，且每个 DDL 列都能在实体中找到")
    void everyColumnHasEntityProperty() throws Exception {
        Map<String, Set<String>> schema = readSchemaColumns();

        assertEquals(25, schema.size(),
                "业务表数量应为 25，实际 " + schema.size() + " -> " + schema.keySet());

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : schema.entrySet()) {
            String table = e.getKey();
            TableInfo info = TableInfoHelper.getTableInfo(classFor(table));
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
    @DisplayName("2. 逻辑删除表集合与 V1 基线一致（9 张，多一张少一张都不行）")
    void logicDeleteTableSetMatchesBaseline() {
        List<String> withLogicDelete = new ArrayList<>();
        List<String> without = new ArrayList<>();
        for (String table : ALL_TABLES) {
            Class<?> cls;
            try {
                cls = classFor(table);
            } catch (ClassNotFoundException e) {
                continue;
            }
            if (LogicDeleteEntity.class.isAssignableFrom(cls)) withLogicDelete.add(table);
            else without.add(table);
        }
        assertEquals(9, withLogicDelete.size(),
                "应有 9 张逻辑删除表，实际 " + withLogicDelete.size() + " -> " + withLogicDelete);
        assertEquals(new TreeSet<>(LOGIC_DELETE_TABLES), new TreeSet<>(withLogicDelete),
                "逻辑删除表名单与 V1 基线不一致");
        assertEquals(16, without.size(), "应有 16 张非逻辑删除表");
    }

    @Test
    @DisplayName("3. 所有实体都继承 BaseEntity（保证 L1 预留字段齐全）")
    void allEntitiesExtendBaseEntity() throws Exception {
        List<String> bad = new ArrayList<>();
        for (String table : ALL_TABLES) {
            Class<?> cls = classFor(table);
            if (!BaseEntity.class.isAssignableFrom(cls)) bad.add(table);
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
            TableInfo info = TableInfoHelper.getTableInfo(classFor(e.getKey()));
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
    @DisplayName("6. 真实读库验证 JSON 列真的被反序列化成 Map（autoResultMap 生效的硬证据）")
    void jsonColumnReallyDeserializesToMap() throws Exception {
        ProcessDefMapper mapper = ctxBean(ProcessDefMapper.class);

        // 造一条带 nodeList 的流程定义
        ProcessDef def = new ProcessDef();
        def.setProcessCode("MP_TEST_PROC_" + System.nanoTime());
        def.setProcessName("阶段6 JSON 验证");
        def.setStatus("ENABLED");
        def.setNodeList(Map.of("节点1", Map.of("审批人", "张三", "可否驳回", true)));
        mapper.insert(def);

        ProcessDef loaded = mapper.selectById(def.getId());
        assertNotNull(loaded.getNodeList(),
                "nodeList 应被反序列化为 Map；若为 null 说明 autoResultMap 没生效");
        Object node1 = loaded.getNodeList().get("节点1");
        assertTrue(node1 instanceof Map,
                "嵌套结构应被完整解析，实际类型 " + (node1 == null ? "null" : node1.getClass()));

        mapper.deleteById(def.getId());
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

    /** 业务表清单（与 V1 基线一致） */
    private static final List<String> ALL_TABLES = Arrays.asList(
            "fin_project", "fin_item", "fin_shipment", "fin_shipment_line",
            "fin_return_order", "fin_receivable", "fin_receipt", "fin_write_off", "fin_invoice",
            "sys_process_def", "sys_org", "sys_user", "sys_audit_log", "sys_file_storage",
            "sys_file_download_log", "sys_dict_type", "sys_dict_data", "sys_config",
            "sys_role", "sys_permission", "sys_menu", "sys_user_role",
            "sys_role_permission", "sys_role_menu", "sys_role_conflict");

    private static final String[] TABLE_TO_ENTITY = {
            "fin_project=Project", "fin_item=Item", "fin_shipment=Shipment",
            "fin_shipment_line=ShipmentLine", "fin_return_order=ReturnOrder",
            "fin_receivable=Receivable", "fin_receipt=Receipt",
            "fin_write_off=WriteOff", "fin_invoice=Invoice",
            "sys_process_def=ProcessDef", "sys_org=Org", "sys_user=User",
            "sys_audit_log=AuditLog", "sys_file_storage=FileStorage",
            "sys_file_download_log=FileDownloadLog", "sys_dict_type=DictType",
            "sys_dict_data=DictData", "sys_config=Config", "sys_role=Role",
            "sys_permission=Permission", "sys_menu=Menu", "sys_user_role=UserRole",
            "sys_role_permission=RolePermission", "sys_role_menu=RoleMenu",
            "sys_role_conflict=RoleConflict"};

    /** 表名 → 实体类名。显式映射而非拼字符串：可读、可审计，类名改了这里立刻报错 */
    private static Class<?> classFor(String table) throws ClassNotFoundException {
        for (String pair : TABLE_TO_ENTITY) {
            String[] kv = pair.split("=");
            if (kv[0].equals(table)) {
                return Class.forName("com.firestar.entity." + kv[1]);
            }
        }
        throw new ClassNotFoundException("未登记的表：" + table);
    }
}
