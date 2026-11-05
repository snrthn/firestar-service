package com.firestar;

import com.firestar.entity.AuditLog;
import com.firestar.mapper.AuditLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 回归测试：审计快照（afterValue/beforeValue）含 java.time.LocalDate 时必须真实落库且可回读。
 *
 * <p>背景：此前 JacksonTypeHandler 内部静态 ObjectMapper 未注册 jsr310，审计异步线程
 *   （audit-log-N）里序列化 Map 中的 LocalDate 会抛 InvalidDefinitionException。该异常只打印在
 *   异步线程 stderr，业务线程已返回、BUILD 仍 SUCCESS，但审计 INSERT 从未执行——记录被静默丢弃
 *   （见 logs/it.log 中 "Exception in thread \"audit-log-1\""）。
 *
 * <p>本测试直接走 AuditLogMapper.insert（同步路径，异常会冒泡到测试线程使其失败），再 selectById
 *   回读，从根上堵住「带日期字段的实体被审计时，审计行静默丢失」的假绿。修复见
 *   com.firestar.config.JacksonTypeHandlerConfig。
 */
@SpringBootTest
@ActiveProfiles("it")
class AuditLogLocalDateSerializationTest {

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Test
    @DisplayName("审计 afterValue 含 LocalDate 时应成功落库，且以 ISO 字符串回读")
    void auditWithLocalDateIsPersisted() {
        AuditLog log = new AuditLog();
        log.setOperationType("UPDATE");
        log.setTargetType("RECEIPT");
        log.setTargetId(999999L);
        log.setOperatorId(1L);
        log.setIpAddress("127.0.0.1");
        log.setRemark("regression: receiptDate LocalDate");

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("receiptDate", LocalDate.of(2026, 11, 5));
        after.put("amount", 100);
        log.setAfterValue(after);
        log.setBeforeValue(new LinkedHashMap<>());

        // 修复前：此处抛 InvalidDefinitionException（LocalDate 无法被默认 ObjectMapper 序列化），测试失败
        auditLogMapper.insert(log);
        assertNotNull(log.getId(), "insert 后应回填主键 id");

        AuditLog saved = auditLogMapper.selectById(log.getId());
        assertNotNull(saved, "含 LocalDate 的审计记录必须真实落库（此前被静默丢弃）");

        Object receiptDate = saved.getAfterValue().get("receiptDate");
        assertNotNull(receiptDate, "回读的 afterValue 应保留 receiptDate 字段");
        assertEquals("2026-11-05", receiptDate.toString(),
                "LocalDate 应以 ISO 字符串落库，实际为 " + receiptDate);

        // 保持 firestar_test 整洁（虽每次 bin/test.sh 都会重建，但避免遗留脏数据）
        auditLogMapper.deleteById(log.getId());
    }
}
