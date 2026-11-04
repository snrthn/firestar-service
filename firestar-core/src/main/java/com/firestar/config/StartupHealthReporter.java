package com.firestar.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.SystemHealth;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 启动自检报告器 —— 把「完整健康」的结论写进 app.log，供运维/部署脚本读取。
 *
 * <h3>为什么需要这个类（解决的是安全收口带来的副作用）</h3>
 * 2026-10-19 安全收口把 {@code /actuator/health} 从匿名白名单移除了（它不该对公网裸奔），
 * 于是 {@code bin/start.sh} 里原来那句「curl /actuator/health 做非阻断式完整健康检查」
 * 只能拿到 401。第一版的处理是在脚本里把 401 判成「跳过完整检查」并打一行提示 ——
 * 这是**把能力丢了**，不是把问题解决了：启动时再也没人能确认「库到底通不通、
 * 表与种子基线对不对」，而那恰恰是启动门禁最该知道的事。
 *
 * <h3>这里的做法：内部信息走日志，不走公网</h3>
 * 应用在同一个 JVM 内直接读 {@link HealthEndpoint}（不走 HTTP，不需要任何凭证），
 * 把聚合状态和每个组件的状态写进日志。{@code show-details=never} 只裁剪 HTTP 响应体，
 * 不影响程序内部读取，所以这里能拿到 db / databaseTable / diskSpace / ping 的逐项状态。
 *
 * <h3>契约（改日志格式前先读）</h3>
 * 日志前缀固定为 {@code [startup-health]}，{@code bin/start.sh} 靠它 grep：
 * <pre>
 *   [startup-health] status=UP components=[db=UP, databaseTable=UP, diskSpace=UP, ping=UP]
 * </pre>
 * 改前缀或字段名必须同步改 start.sh，否则启动时会打「未拿到启动自检结果」。
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>任何异常都只记日志，<b>绝不影响启动</b>（ApplicationRunner 抛异常会让应用启动失败，
 *       而健康检查本身失败正是我们想观察的现象，不能反过来把它变成启动故障）；</li>
 *   <li>{@code @Order(LOWEST_PRECEDENCE)}：排在其它 Runner 之后，避免数据初始化还没跑完就下结论；</li>
 *   <li>非 UP 用 WARN：prod 的 {@code logging.level.com.firestar=warn} 下仍会落盘。</li>
 * </ul>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StartupHealthReporter implements ApplicationRunner {

    /** 与 bin/start.sh 的 grep 约定，勿单独修改 */
    public static final String TAG = "[startup-health]";

    private static final Logger log = LoggerFactory.getLogger(StartupHealthReporter.class);

    /** 用 ObjectProvider：actuator 未启用时也不该让应用起不来 */
    private final ObjectProvider<HealthEndpoint> healthEndpointProvider;

    public StartupHealthReporter(ObjectProvider<HealthEndpoint> healthEndpointProvider) {
        this.healthEndpointProvider = healthEndpointProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        HealthEndpoint endpoint = healthEndpointProvider.getIfAvailable();
        if (endpoint == null) {
            log.info("{} 未启用 actuator health 端点，跳过启动自检", TAG);
            return;
        }
        try {
            HealthComponent health = endpoint.health();
            String status = health.getStatus().getCode();
            String components = describe(health);
            if ("UP".equals(status)) {
                log.info("{} status={} components={}", TAG, status, components);
            } else {
                log.warn("{} status={} components={} —— 端口已就绪但健康检查未通过："
                                + "看本行哪个组件 DOWN；登录后访问 /system/db-check 可拿到完整结论",
                        TAG, status, components);
            }
        } catch (Exception e) {
            // 健康检查自身出错（如库连接池未初始化完成）不能反噬启动流程
            log.warn("{} 启动自检执行失败（不影响启动）: {}", TAG, e.toString());
        }
    }

    private String describe(HealthComponent health) {
        // /actuator/health 的聚合结果是 SystemHealth（Spring Boot 3.1+），
        // getComponents() 只在这个具体类型上；HealthComponent / Health 都没有。
        // 用 instanceof 而不是硬转：换实现/升级 Boot 时退化为「只报总状态」，不会 ClassCastException。
        Map<String, HealthComponent> comps = (health instanceof SystemHealth sh) ? sh.getComponents() : null;
        if (comps == null || comps.isEmpty()) {
            return "[]";
        }
        return comps.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().getStatus().getCode())
                .sorted()
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
