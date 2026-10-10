package com.firestar.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 让 Flyway 的迁移先于 Flowable 各引擎完成。
 *
 * 【背景：为什么必须存在这个类】
 * 我们把 Flowable 引擎表的建表职责从「引擎自建」改为「Flyway V3 迁移」，
 * 于是产生了一个**启动顺序依赖**：
 *
 *   Flyway V3 建表 → Flowable 引擎才能读 ACT_GE_PROPERTY 里的版本号
 *
 * 但 Spring Boot 默认的 {@code FlywayInitializer} 只保证「早于 JPA」，
 * **不保证早于 Flowable**。实测（dev 库删掉引擎表后启动）：
 *
 *   org.flowable.common.engine.api.FlowableException:
 *     SQL Exception when getting value. Table 'firestar.act_ge_property' doesn't exist
 *     at AbstractSqlScriptBasedDbSchemaManager.getProperty(:202)
 *     at EngineSqlScriptBasedDbSchemaManager.getDbVersion(:182)
 *     at EngineSqlScriptBasedDbSchemaManager.schemaCheckVersion(:49)
 *
 * 注意：**这跟 flowable.database-schema-update=false 无关**。
 * 该开关只表示「不要自动建表」，引擎启动时**依然会读版本号做校验**，
 * 读不到就直接抛异常。所以只要引擎表改由 Flyway 管，就必须保证 Flyway 先跑完。
 * 这个坑在生产环境一模一样会炸，不是只在本地出现。
 *
 * 【为什么用 BeanFactoryPostProcessor 而不是 @DependsOn】
 * Flowable 的六个引擎（process / idm / dmn / cmmn / app / event-registry）由
 * spring-boot-starter 自动装配，bean 名由框架决定、无法在业务代码里直接标注。
 * 与其逐个去改框架创建的 bean 定义，不如在这里统一给所有 Flowable 引擎 bean
 * 追加 dependsOn=flywayInitializer。
 *
 * 【为什么不能用 @Order / PriorityOrdered】
 * BeanFactoryPostProcessor 本身就是最早执行的一批，
 * 此时 bean 定义尚未实例化，正是追加 dependsOn 的正确时机。
 * Ordered 在这里没有任何作用。
 *
 * 【降级保护】
 * 若容器里没有 flywayInitializer（例如某个 profile 显式关闭了 Flyway），
 * 绝不能给引擎强加一个不存在的依赖 ——
 * 那会把问题从「表不存在」变成 NoSuchBeanDefinitionException，更难排查。
 * 所以先 containsBean 判断。
 */
@Configuration
public class FlywayBeforeFlowableConfig {

    /**
     * Flowable 各引擎配置 bean 的名字特征。
     * 覆盖 spring-boot-starter-process / -idm / -dmn / -cmmn / -app / -event-registry。
     *
     * 用「包含 engineConfiguration」而非穷举具体名字，
     * 是为了 Flowable 升级新增引擎时不漏掉。
     */
    private static final String FLOWABLE_ENGINE_MARKER = "engineconfiguration";

    private static final String FLYWAY_INITIALIZER = "flywayInitializer";

    @Bean
    public static BeanFactoryPostProcessor flywayInitializerFirst() {
        return new BeanFactoryPostProcessor() {
            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
                    throws BeansException {
                if (!beanFactory.containsBeanDefinition(FLYWAY_INITIALIZER)) {
                    // 没有 Flyway（如某些 profile 显式关闭），不做任何干预
                    return;
                }
                for (String beanName : beanFactory.getBeanDefinitionNames()) {
                    if (!beanName.toLowerCase().contains(FLOWABLE_ENGINE_MARKER)) {
                        continue;
                    }
                    beanFactory.getBeanDefinition(beanName)
                            .setDependsOn(FLYWAY_INITIALIZER);
                }
            }
        };
    }
}