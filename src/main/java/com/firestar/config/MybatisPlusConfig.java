package com.firestar.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 全局配置。
 *
 * 【拦截器顺序铁律】官方顺序：多租户 → 动态表名 → 分页 → 乐观锁 → 防全表更新。
 * 本项目 V1 暂不启用 TenantLineInnerInterceptor（原因见 application.properties 注释），
 * 但**顺序位已预留**：将来启用时必须插在 Pagination 之前，不能追加到最后。
 *
 * 【为什么分页放这里而不是用 MP starter 自动装配】
 * 显式声明 PaginationInnerInterceptor 才能锁定单页上限 500，
 * 避免误传 size=99999 把单表拖进全表扫描。
 */
@Configuration
@MapperScan({"com.firestar.mapper", "com.firestar.ai.mapper"})
public class MybatisPlusConfig {

    /** 单页最大条数上限，超出按此值截断 */
    private static final long MAX_PAGE_SIZE = 500L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1) 预留位：多租户插件。V1 全部 tenant_id = 0，启用前不得加入此列表。
        //    interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantHandler()));

        // 2) 分页插件（必须在乐观锁之前）
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        pagination.setOverflow(false); // 页码越界时返回空列表，而不是回到第一页
        interceptor.addInnerInterceptor(pagination);

        // 3) 乐观锁：驱动 @Version 字段，UPDATE 时自动追加 version = version + 1 并校验
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 4) 防全表更新/删除：拦截无 WHERE 的 UPDATE/DELETE
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}
