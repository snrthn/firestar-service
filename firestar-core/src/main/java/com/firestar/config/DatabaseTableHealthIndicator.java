package com.firestar.config;

import com.firestar.service.DatabaseCheckService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 把数据库自检结果接入 actuator。
 *
 * 【为什么需要它】
 * 标准 actuator 的 db 检查只测「连得上吗」。但连上一个空库它同样报 UP，
 * 而"库是空的"恰恰是本项目部署时最可能出事的场景（初始化漏跑、连错 schema）。
 * 这里把"表建全了没、种子数据灌了没"也算进健康状态，
 * 这样 CI 现有的 `curl /actuator/health` 轮询就能发现初始化问题，
 * 不必等业务请求报 500 才知道。
 *
 * 【状态映射】
 *   UP      → 全部检查通过
 *   DOWN    → 连接失败，或表数/种子数据与 V1 基线不符
 *   OUT_OF_SERVICE → 暂未使用
 *
 * 注意：details 里只有当 management.endpoint.health.show-details 放开时才可见；
 * 默认 never 是刻意收紧的（避免泄漏库名、表名），本类不改变这一点。
 */
@Component
public class DatabaseTableHealthIndicator implements HealthIndicator {

    private final DatabaseCheckService databaseCheckService;

    public DatabaseTableHealthIndicator(DatabaseCheckService databaseCheckService) {
        this.databaseCheckService = databaseCheckService;
    }

    @Override
    public Health health() {
        try {
            Map<String, Object> report = databaseCheckService.check();
            @SuppressWarnings("unchecked")
            List<String> errors = (List<String>) report.getOrDefault("errors", List.of());

            if (Boolean.TRUE.equals(report.get("healthy"))) {
                return Health.up()
                        .withDetail("业务表数量", tableCount(report))
                        .withDetail("Flyway 版本", flywayVersion(report))
                        .withDetail("耗时ms", report.get("costMs"))
                        .build();
            }
            // Health.down() 没有接收 List 的重载，只能逐条挂 detail。
            // 再补一条汇总，方便只扫一眼 details 的监控系统快速定位。
            Health.Builder down = Health.down();
            int idx = 1;
            for (String err : errors) {
                down.withDetail("异常" + (idx++), err);
            }
            down.withDetail("异常总数", errors.size());
            down.withDetail("业务表数量", tableCount(report));
            down.withDetail("Flyway 版本", flywayVersion(report));
            return down.build();
        } catch (Exception e) {
            // 自检本身炸了不能连带把健康端点搞崩，降级为 DOWN 并带上原因
            return Health.down(e).build();
        }
    }

    private Object tableCount(Map<String, Object> report) {
        Object tables = report.get("tables");
        if (tables instanceof Map<?, ?> m) {
            return m.get("businessTableCount") + " / " + m.get("expected");
        }
        return "N/A";
    }

    private Object flywayVersion(Map<String, Object> report) {
        Object flyway = report.get("flyway");
        if (flyway instanceof Map<?, ?> m) {
            return m.get("currentVersion");
        }
        return "N/A";
    }
}
