package com.firestar.config;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * 修复 JacksonTypeHandler 对 java.time 类型的序列化盲区。
 *
 * <p>问题：MyBatis-Plus 的 {@link JacksonTypeHandler} 持有自身内部的静态 ObjectMapper，
 *   默认未注册 jackson-datatype-jsr310。当 JSON 列（sys_audit_log 的 before_value/after_value、
 *   BaseEntity.ext、ProcessDef、Role、InvoiceAllocation.ext、WriteOff.ext）的 Map 中混入
 *   java.time.LocalDate / LocalDateTime 值时，序列化会抛 InvalidDefinitionException。
 *   该异常发生在异步审计线程（audit-log-N）上——业务线程早已返回、测试仍绿，
 *   但审计 INSERT 从未执行，记录被静默丢弃（违反 ExecutorConfig「不丢审计」的设计意图）。
 *
 * <p>修复：启动时把带 JavaTimeModule 的 ObjectMapper 一次性注入 JacksonTypeHandler 的静态实例。
 *   日期以 ISO 字符串（如 2026-11-05 / 2026-11-05T10:00:00）落库，读回仍是字符串，
 *   对既有非日期内容零回归。所有 JacksonTypeHandler 支撑的 JSON 列统一受益。
 */
@Configuration
public class JacksonTypeHandlerConfig {

    @PostConstruct
    public void configureJacksonTypeHandler() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JacksonTypeHandler.setObjectMapper(mapper);
    }
}
