package com.firestar.seed;

import com.firestar.entity.Menu;
import com.firestar.mapper.MenuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动期种子完整性校验（非阻塞、仅告警）。
 *
 * <p>应用启动后读取全量 {@code sys_menu}，跑 {@link SeedIntegrityCheck}。发现违规只打 WARN，
 * 不影响启动——这是为了「尽早暴露种子回归（route_path 漏写 /、BUTTON 节点缺失）」，
 * 而非阻断发版。CI 侧可用 {@code SeedIntegrityTest} 把同类校验变成「测试失败」。</p>
 */
@Component
@Order(100)
public class SeedIntegrityValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedIntegrityValidator.class);

    private final MenuMapper menuMapper;

    public SeedIntegrityValidator(MenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Menu> all = menuMapper.selectList(null);
            List<String> violations = SeedIntegrityCheck.check(all);
            if (violations.isEmpty()) {
                log.info("[种子完整性] 校验通过：共 {} 个菜单节点，无 route_path / BUTTON 缺失问题。", all.size());
            } else {
                log.warn("[种子完整性] 校验发现 {} 项问题（非阻塞，仅告警）：\n{}",
                        violations.size(), String.join("\n", violations));
            }
        } catch (Exception e) {
            // 非阻塞：读库异常（如表尚未建好、Flyway 被关）不影响启动
            log.warn("[种子完整性] 校验跳过（读库异常，不影响启动）：{}", e.getMessage());
        }
    }
}
