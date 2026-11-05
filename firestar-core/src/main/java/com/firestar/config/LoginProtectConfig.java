package com.firestar.config;

import com.firestar.service.LoginProtectService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 登录爆破防护装配。
 *
 * <p>把 {@link LoginRateLimitFilter} 以 {@link FilterRegistrationBean} 显式注册，仅拦截
 * {@code /auth/login}、最高优先级，确保早于 Spring Security 认证链执行。
 * 注意：过滤器本身<b>不</b>标注 {@code @Component}，避免被 Spring Boot 默认注册到全部路径；
 * 仅由此处 {@code FilterRegistrationBean} 接管注册。
 */
@Configuration
public class LoginProtectConfig {

    @Bean
    public LoginRateLimitFilter loginRateLimitFilter(LoginProtectService protectService) {
        return new LoginRateLimitFilter(protectService);
    }

    @Bean
    public FilterRegistrationBean<LoginRateLimitFilter> loginRateLimitFilterRegistration(
            LoginRateLimitFilter loginRateLimitFilter) {
        FilterRegistrationBean<LoginRateLimitFilter> bean = new FilterRegistrationBean<>(loginRateLimitFilter);
        bean.addUrlPatterns("/auth/login");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
